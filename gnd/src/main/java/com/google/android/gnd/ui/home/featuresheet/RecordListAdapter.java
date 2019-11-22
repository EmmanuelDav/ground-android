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

package com.google.android.gnd.ui.home.featuresheet;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.gnd.databinding.RecordListItemBinding;
import com.google.android.gnd.model.observation.Observation;
import com.google.android.gnd.ui.common.ViewModelFactory;
import java.util.Collections;
import java.util.List;

// TODO: Consider passing in ViewModel and using DataBinding like todoapp example.
class RecordListAdapter extends RecyclerView.Adapter<RecordListItemViewHolder> {

  private final ViewModelFactory viewModelFactory;
  private List<Observation> observationList;
  private MutableLiveData<Observation> itemClicks;

  public RecordListAdapter(ViewModelFactory viewModelFactory) {
    this.viewModelFactory = viewModelFactory;
    observationList = Collections.emptyList();
    itemClicks = new MutableLiveData<>();
  }

  @NonNull
  @Override
  public RecordListItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    RecordListItemBinding itemBinding = RecordListItemBinding.inflate(inflater, parent, false);
    return new RecordListItemViewHolder(itemBinding);
  }

  @Override
  public void onBindViewHolder(@NonNull RecordListItemViewHolder holder, int position) {
    RecordViewModel viewModel = viewModelFactory.create(RecordViewModel.class);
    viewModel.setRecord(observationList.get(position));
    viewModel.setRecordCallback(record -> itemClicks.postValue(record));
    holder.bind(viewModel, observationList.get(position));
  }

  @Override
  public int getItemCount() {
    return observationList.size();
  }

  LiveData<Observation> getItemClicks() {
    return itemClicks;
  }

  void clear() {
    this.observationList = Collections.emptyList();
    notifyDataSetChanged();
  }

  void update(List<Observation> observationList) {
    this.observationList = observationList;
    notifyDataSetChanged();
  }
}
