/*
 * Copyright 2021 Qbeast Analytics, S.L.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.connector.catalog

import org.apache.spark.sql.types.StructType

object SparkCatalogV2Util {

  /**
   * Converts DS v2 columns to StructType, which encodes column comment and default value to
   * StructField metadata. This is mainly used to define the schema of v2 scan, w.r.t. the columns
   * of the v2 table.
   */
  def v2ColumnsToStructType(columns: Array[Column]): StructType = {
    CatalogV2Util.v2ColumnsToStructType(columns)
  }

  def structTypeToV2Columns(schema: StructType): Array[Column] = {
    CatalogV2Util.structTypeToV2Columns(schema)
  }

}
