/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.guice;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

public class DruidExtensionDependencies
{

  @JsonProperty("dependsOnDruidExtensions")
  private List<String> dependsOnDruidExtensions;

  public DruidExtensionDependencies()
  {
    this.dependsOnDruidExtensions = new ArrayList<>();
  }

  public DruidExtensionDependencies(@Nonnull final List<String> dependsOnDruidExtensions)
  {
    this.dependsOnDruidExtensions = dependsOnDruidExtensions;
  }

  public List<String> getDependsOnDruidExtensions()
  {
    return dependsOnDruidExtensions;
  }
}
