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

package cc.gospy.core.scheduler.queue;

import cc.gospy.core.entity.Task;

import java.util.Iterator;
import java.util.PriorityQueue;

public abstract class LazyTaskQueue extends TaskQueue {
    protected LazyTaskHandler handler;

    protected LazyTaskQueue(LazyTaskHandler handler) {
        this.handler = handler;
    }

    protected abstract boolean ready();

    protected PriorityQueue<Task> lazyTaskQueue;

    @Override
    public Task poll() {
        // assert lazyTaskQueue != null;
        Task task = ready() ? lazyTaskQueue.poll() : null;
        if (task != null)
            handler.invoke(task);
        return task;
    }

    public Iterator<Task> dump() {
        try {
            return lazyTaskQueue.iterator();
        } finally {
            lazyTaskQueue.clear();
        }
    }

    public abstract void stop();

}
