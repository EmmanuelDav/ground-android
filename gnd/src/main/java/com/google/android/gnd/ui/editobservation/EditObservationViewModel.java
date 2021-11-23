/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gnd.ui.editobservation;

import static androidx.lifecycle.LiveDataReactiveStreams.fromPublisher;
import static com.google.android.gnd.persistence.remote.firestore.FirestoreStorageManager.getRemoteMediaPath;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.res.Resources;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.android.gnd.R;
import com.google.android.gnd.model.form.Element;
import com.google.android.gnd.model.form.Element.Type;
import com.google.android.gnd.model.form.Field;
import com.google.android.gnd.model.form.Form;
import com.google.android.gnd.model.observation.Observation;
import com.google.android.gnd.model.observation.Response;
import com.google.android.gnd.model.observation.ResponseDelta;
import com.google.android.gnd.model.observation.ResponseMap;
import com.google.android.gnd.repository.ObservationRepository;
import com.google.android.gnd.repository.UserMediaRepository;
import com.google.android.gnd.rx.Nil;
import com.google.android.gnd.rx.annotations.Cold;
import com.google.android.gnd.rx.annotations.Hot;
import com.google.android.gnd.system.CameraManager;
import com.google.android.gnd.system.StorageManager;
import com.google.android.gnd.ui.common.AbstractViewModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.processors.BehaviorProcessor;
import io.reactivex.processors.FlowableProcessor;
import io.reactivex.processors.PublishProcessor;
import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java8.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import timber.log.Timber;

public class EditObservationViewModel extends AbstractViewModel {

  // Injected dependencies.

  private final ObservationRepository observationRepository;
  private final Resources resources;
  private final UserMediaRepository userMediaRepository;
  private final StorageManager storageManager;
  private final CameraManager cameraManager;

  // States.

  /** True if observation is currently being loaded, otherwise false. */
  @Hot(replays = true)
  public final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);

  /** True if observation is currently being saved, otherwise false. */
  @Hot(replays = true)
  public final MutableLiveData<Boolean> isSaving = new MutableLiveData<>(false);

  /** Form definition, loaded when view is initialized. */
  private final LiveData<Form> form;

  /** Toolbar title, based on whether user is adding new or editing existing observation. */
  @Hot(replays = true)
  private final MutableLiveData<String> toolbarTitle = new MutableLiveData<>();

  /** Current form responses. */
  private final Map<String, Response> responses = new HashMap<>();

  /** Form validation errors, updated when existing for loaded and when responses change. */
  @Nullable private Map<String, String> validationErrors;

  /** Arguments passed in from view on initialize(). */
  @Hot(replays = true)
  private final FlowableProcessor<EditObservationFragmentArgs> viewArgs =
      BehaviorProcessor.create();

  /** Observation state loaded when view is initialized. */
  @Nullable private Observation originalObservation;

  /** True if the observation is being added, false if editing an existing one. */
  private boolean isNew;

  // Events.

  /** "Save" button clicks. */
  @Hot private final PublishProcessor<Nil> saveClicks = PublishProcessor.create();

  /** Outcome of user clicking "Save". */
  private final Observable<SaveResult> saveResults;

  /** Stream of updates to photo fields. */
  @Hot(replays = true)
  private final MutableLiveData<ImmutableMap<Field, String>> photoUpdates = new MutableLiveData<>();

  @Inject
  EditObservationViewModel(
      Resources resources,
      ObservationRepository observationRepository,
      UserMediaRepository userMediaRepository,
      StorageManager storageManager,
      CameraManager cameraManager) {
    this.resources = resources;
    this.observationRepository = observationRepository;
    this.userMediaRepository = userMediaRepository;
    this.storageManager = storageManager;
    this.cameraManager = cameraManager;
    this.form = fromPublisher(viewArgs.switchMapSingle(this::onInitialize));
    this.saveResults = saveClicks.toObservable().switchMapSingle(__ -> onSave());
  }

  private static boolean isAddObservationRequest(EditObservationFragmentArgs args) {
    return args.getObservationId().isEmpty();
  }

  public LiveData<Form> getForm() {
    return form;
  }

  public LiveData<String> getToolbarTitle() {
    return toolbarTitle;
  }

  Observable<SaveResult> getSaveResults() {
    return saveResults;
  }

  void initialize(EditObservationFragmentArgs args) {
    viewArgs.onNext(args);
  }

  Optional<Response> getResponse(String fieldId) {
    return Optional.ofNullable(responses.get(fieldId));
  }

  /**
   * Update the current value of a response. Called what fields are initialized and on each
   * subsequent change.
   */
  void setResponse(Field field, Optional<Response> newResponse) {
    newResponse.ifPresentOrElse(
        r -> responses.put(field.getId(), r), () -> responses.remove(field.getId()));
  }

  public void showPhotoSelector(Field field) {
    /*
     * Didn't subscribe this with Fragment's lifecycle because we need to retain the disposable
     * after the fragment is destroyed (for activity result)
     */
    // TODO: launch intent through fragment and handle activity result callbacks async
    disposeOnClear(
        storageManager
            .selectPhoto()
            .doOnError(Timber::e) // TODO(#726): Display as a toast
            .map(bitmap -> userMediaRepository.savePhoto(bitmap, field))
            .flatMapCompletable(file -> onPhotoSaved(field, file))
            .subscribe());
  }

  public void showPhotoCapture(Field field) {
    File imageFile = userMediaRepository.createImageFile(field);

    /*
     * Didn't subscribe this with Fragment's lifecycle because we need to retain the disposable
     * after the fragment is destroyed (for activity result)
     */
    // TODO: launch intent through fragment and handle activity result callbacks async
    disposeOnClear(
        cameraManager
            .capturePhoto(imageFile)
            .doOnError(Timber::e) // TODO(#726): Display as a toast
            .flatMapCompletable(__ -> onPhotoSaved(field, imageFile))
            .subscribe());
  }

  @Cold
  private Completable onPhotoSaved(Field field, File imageFile) {
    return Completable.fromAction(
        () -> {
          String filename = imageFile.getName();

          // Add image to gallery
          userMediaRepository.addImageToGallery(imageFile.getAbsolutePath(), filename);

          // Update response
          checkNotNull(originalObservation);
          String remoteDestinationPath = getRemoteMediaPath(originalObservation, filename);
          photoUpdates.postValue(ImmutableMap.of(field, remoteDestinationPath));
        });
  }

  LiveData<ImmutableMap<Field, String>> getPhotoFieldUpdates() {
    return photoUpdates;
  }

  public void onSaveClick(Map<String, String> validationErrors) {
    this.validationErrors = validationErrors;
    saveClicks.onNext(Nil.NIL);
  }

  private Single<Form> onInitialize(EditObservationFragmentArgs viewArgs) {
    isLoading.setValue(true);
    isNew = isAddObservationRequest(viewArgs);
    Single<Observation> obs;
    if (isNew) {
      toolbarTitle.setValue(resources.getString(R.string.add_observation_toolbar_title));
      obs = createObservation(viewArgs);
    } else {
      toolbarTitle.setValue(resources.getString(R.string.edit_observation));
      obs = loadObservation(viewArgs);
    }
    HashMap<String, Response> restoredResponses = viewArgs.getRestoredResponses();
    return obs.doOnSuccess(
            loadedObservation -> onObservationLoaded(loadedObservation, restoredResponses))
        .map(Observation::getForm);
  }

  private void onObservationLoaded(
      Observation observation, @Nullable Map<String, Response> restoredResponses) {
    Timber.v("Observation loaded");
    this.originalObservation = observation;
    responses.clear();
    if (restoredResponses == null) {
      ResponseMap responseMap = observation.getResponses();
      for (String fieldId : responseMap.fieldIds()) {
        responseMap.getResponse(fieldId).ifPresent(r -> responses.put(fieldId, r));
      }
    } else {
      Timber.v("Restoring responses from bundle");
      responses.putAll(restoredResponses);
    }
    isLoading.postValue(false);
  }

  private Single<Observation> createObservation(EditObservationFragmentArgs args) {
    return observationRepository
        .createObservation(args.getProjectId(), args.getFeatureId(), args.getFormId())
        .onErrorResumeNext(this::onError);
  }

  private Single<Observation> loadObservation(EditObservationFragmentArgs args) {
    return observationRepository
        .getObservation(args.getProjectId(), args.getFeatureId(), args.getObservationId())
        .onErrorResumeNext(this::onError);
  }

  private Single<SaveResult> onSave() {
    if (originalObservation == null) {
      Timber.e("Save attempted before observation loaded");
      return Single.just(SaveResult.NO_CHANGES_TO_SAVE);
    }

    if (hasValidationErrors()) {
      return Single.just(SaveResult.HAS_VALIDATION_ERRORS);
    }
    if (!hasUnsavedChanges()) {
      return Single.just(SaveResult.NO_CHANGES_TO_SAVE);
    }
    return save();
  }

  private <T> Single<T> onError(Throwable throwable) {
    // TODO: Refactor and stream to UI.
    Timber.e(throwable, "Error");
    return Single.never();
  }

  private Single<SaveResult> save() {
    if (originalObservation == null) {
      return Single.error(new IllegalStateException("Observation is null"));
    }

    return observationRepository
        .createOrUpdateObservation(originalObservation, getResponseDeltas(), isNew)
        .doOnSubscribe(__ -> isSaving.postValue(true))
        .doOnComplete(() -> isSaving.postValue(false))
        .toSingleDefault(SaveResult.SAVED);
  }

  private ImmutableList<ResponseDelta> getResponseDeltas() {
    if (originalObservation == null) {
      Timber.e("Response diff attempted before observation loaded");
      return ImmutableList.of();
    }
    ImmutableList.Builder<ResponseDelta> deltas = ImmutableList.builder();
    ResponseMap originalResponses = originalObservation.getResponses();
    Timber.v("Responses:\n Before: %s \nAfter:  %s", originalResponses, responses);
    for (Element e : originalObservation.getForm().getElements()) {
      if (e.getType() != Type.FIELD) {
        continue;
      }
      String fieldId = e.getField().getId();
      Optional<Response> originalResponse = originalResponses.getResponse(fieldId);
      Optional<Response> currentResponse = getResponse(fieldId).filter(r -> !r.isEmpty());
      if (currentResponse.equals(originalResponse)) {
        continue;
      }
      deltas.add(
          ResponseDelta.builder()
              .setFieldId(fieldId)
              .setFieldType(e.getField().getType())
              .setNewResponse(currentResponse)
              .build());
    }
    ImmutableList<ResponseDelta> result = deltas.build();
    Timber.v("Deltas: %s", result);
    return result;
  }

  boolean hasUnsavedChanges() {
    return !getResponseDeltas().isEmpty();
  }

  private boolean hasValidationErrors() {
    return validationErrors != null && !validationErrors.isEmpty();
  }

  public Serializable getDraftResponses() {
    HashMap<String, Response> hashMap = new HashMap<>();
    hashMap.putAll(responses);
    return hashMap;
  }

  /** Possible outcomes of user clicking "Save". */
  enum SaveResult {
    HAS_VALIDATION_ERRORS,
    NO_CHANGES_TO_SAVE,
    SAVED
  }
}
