/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.repository.graphdb;

import java.util.Set;

/** TODO Janus-Graph中的索引技术，包括混合索引与组合索引两种 * https://juejin.im/post/5e57d2ebe51d452726151c37
 * Represents a graph index on the database.
 */
public interface AtlasGraphIndex {

    /** 混合索引
     * Indicates if the index is a mixed index.
     * @return
     */
    boolean isMixedIndex();


    /** 组合索引
     * Indicates if the index is a composite index.
     * @return
     */
    boolean isCompositeIndex();

    /**
     * Indicates if the index applies to edges.
     * 是否为边索引
     * @return
     */
    boolean isEdgeIndex();

    /**
     * Indicates if the index applies to vertices.
     * 是否为顶点索引
     * @return
     */
    boolean isVertexIndex();



    boolean isUnique();

    Set<AtlasPropertyKey> getFieldKeys();

}
