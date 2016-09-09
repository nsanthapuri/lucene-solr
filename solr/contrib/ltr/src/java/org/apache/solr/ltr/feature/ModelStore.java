/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.ltr.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.ltr.util.ModelException;

/**
 * Contains the model and features declared.
 */
public class ModelStore {

  private final Map<String,LTRScoringAlgorithm> availableModels;

  public ModelStore() {
    availableModels = new HashMap<>();
  }

  public synchronized LTRScoringAlgorithm getModel(String name) {
    return availableModels.get(name);
  }

  public boolean containsModel(String modelName) {
    return availableModels.containsKey(modelName);
  } 

  public void clear() {
    availableModels.clear();
  }
  
  public int size() {
    return availableModels.size();
  }
  
  public List<LTRScoringAlgorithm> getModels() {
    final List<LTRScoringAlgorithm> availableModelsValues = 
        new ArrayList<LTRScoringAlgorithm>(availableModels.values());
    return Collections.unmodifiableList(availableModelsValues);
  }

  @Override
  public String toString() {
    return "ModelStore [availableModels=" + availableModels.keySet() + "]";
  }

  public void delete(String modelName) {
    availableModels.remove(modelName);
  }

  public synchronized void addModel(LTRScoringAlgorithm modeldata)
      throws ModelException {
    final String name = modeldata.getName();

    if (containsModel(name)) {
      throw new ModelException("model '" + name
          + "' already exists. Please use a different name");
    }

    availableModels.put(modeldata.getName(), modeldata);
  }

}
