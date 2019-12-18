/*
 * Copyright 2019 Google LLC
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

package com.google.android.gnd.persistence.local.room;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Query;
import androidx.room.Transaction;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import java.util.List;

@Dao
public interface ProjectDao extends BaseDao<ProjectEntity> {

  @Delete
  Completable deleteProject(ProjectEntity projectEntity);

  @Transaction
  @Query("SELECT * FROM project")
  Single<List<ProjectEntityAndRelations>> getAllProjects();

  @Transaction
  @Query("SELECT * FROM project WHERE id = :id")
  Maybe<ProjectEntityAndRelations> getProjectById(String id);
}