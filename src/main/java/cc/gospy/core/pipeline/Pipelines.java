/*
 * Copyright 2017 ZhangJiupeng
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

package cc.gospy.core.pipeline;

import cc.gospy.core.pipeline.impl.ConsolePipeline;
import cc.gospy.core.pipeline.impl.HierarchicalFilePipeline;
import cc.gospy.core.pipeline.impl.SimpleFilePipeline;
import cc.gospy.core.remote.hprose.RemotePipeline;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Set;

public class Pipelines {
    public static HierarchicalFilePipeline HierarchicalFilePipeline;
    public static SimpleFilePipeline SimpleFilePipeline;
    public static ConsolePipeline ConsolePipeline;
    public static RemotePipeline RemotePipeline;

    private ArrayListMultimap<Class<?>, Pipeline> pipelines = ArrayListMultimap.create();

    public void register(Pipeline pipeline) {
        if (pipeline == null) {
            throw new RuntimeException("pipeline not initialized, please check your code.");
        }
        pipelines.put(pipeline.getAcceptedDataType(), pipeline);
    }

    public <T> Collection<Pipeline> get(Class<T> resultType) throws PipelineNotFoundException {
        Set<Pipeline> activatedPipelines = Sets.newLinkedHashSet(this.pipelines.get(resultType));
        if (resultType != Object.class) {
            // add universal pipelines
            activatedPipelines.addAll(this.pipelines.get(Object.class));
        }
        return activatedPipelines;
    }

    public Collection<Pipeline> getAll() {
        return pipelines.values();
    }
}
