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

import cc.gospy.core.entity.Result;
import cc.gospy.core.entity.Task;

import java.util.Collection;

public abstract class PageProcessor {
    protected Task task;

    public abstract void process();

    public abstract Collection<Task> getNewTasks();

    public Task getFeedback() {
        return task;
    }

    public abstract <T> T getResultData();

    private <T> Result<T> getResult() {
        return new Result<>(getNewTasks(), getResultData());
    }

    public void setTask(Task task) {
        this.task = task;
    }
}
