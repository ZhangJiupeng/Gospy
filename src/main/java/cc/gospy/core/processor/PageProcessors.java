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

package cc.gospy.core.processor;

import cc.gospy.core.util.Experimental;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Experimental
public class PageProcessors {
    private Map<String, Class<? extends PageProcessor>> pageProcessors = new LinkedHashMap<>();

    public void register(Class<? extends PageProcessor> processorClazz) {
        if (processorClazz == null) {
            throw new RuntimeException("page processor not declared, please check your code.");
        }
        UrlPattern urlPattern = processorClazz.getAnnotation(UrlPattern.class);
        if (urlPattern == null) {
            throw new RuntimeException("annotation \"cc.gospy.core.processor.UrlPattern\" not found, please declare url patterns first for your processor.");
        }
        if (!Modifier.isStatic(processorClazz.getModifiers())) {
            throw new RuntimeException("page processor should be static, please check your code.");
        }
        for (String pattern : urlPattern.value()) {
            pageProcessors.put(pattern, processorClazz);
        }
    }

    public Class<? extends PageProcessor> get(String url) throws PageProcessorNotFoundException {
        for (String pattern : pageProcessors.keySet()) {
            if (url.matches(pattern)) {
                return pageProcessors.get(pattern);
            }
        }
        throw new PageProcessorNotFoundException(url);
    }

    public Collection<Class<? extends PageProcessor>> getAll() {
        return pageProcessors.values();
    }
}
