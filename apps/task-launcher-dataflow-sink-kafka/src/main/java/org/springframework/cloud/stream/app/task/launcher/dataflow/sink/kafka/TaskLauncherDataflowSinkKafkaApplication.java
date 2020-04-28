/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.task.launcher.dataflow.sink.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.dataflow.rest.client.config.DataFlowClientAutoConfiguration;
import org.springframework.context.annotation.Import;


@SpringBootApplication(exclude = DataFlowClientAutoConfiguration.class)
@Import(org.springframework.cloud.stream.app.task.launcher.dataflow.sink.TaskLauncherDataflowSinkConfiguration.class)
public class TaskLauncherDataflowSinkKafkaApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaskLauncherDataflowSinkKafkaApplication.class, args);
	}
}
