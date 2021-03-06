/*
 * Copyright 2018 Google LLC
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

import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import com.google.protobuf.gradle.plugins
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.remove
import com.google.protobuf.gradle.generateProtoTasks
import com.google.protobuf.gradle.ofSourceSet

plugins {
    idea
    maven
    kotlin("jvm")
    `java-library`
    id("com.google.protobuf") version "0.8.8"
    jacoco
}

base {
    archivesBaseName = "kgax-grpc-android"
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation("javax.annotation:javax.annotation-api:${ext["javax_annotation_version"]}")

    api(project(":kgax-grpc-base"))

    api("io.grpc:grpc-okhttp:${ext["grpc_version"]}")
    api("io.grpc:grpc-android:${ext["grpc_version"]}")
    api("io.grpc:grpc-protobuf-lite:${ext["grpc_version"]}")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:${ext["junit_version"]}")
    testImplementation("com.nhaarman:mockito-kotlin:${ext["mockito_kotlin_version"]}")
    testImplementation("com.google.truth:truth:${ext["truth_version"]}")
}

java {
    sourceSets {
        getByName("main").proto.srcDir("$projectDir/../api-common-protos")
        getByName("main").proto.srcDir("$projectDir/../api-android-protos")
    }
}

jacoco {
    toolVersion = "0.8.2"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${ext["protoc_version"]}"
    }
    plugins {
        id("javalite") {
            artifact = "com.google.protobuf:protoc-gen-javalite:${ext["protoc_gen_javalite_version"]}"
        }
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.builtins {
                remove("java")
            }
            it.plugins {
                id("javalite")
            }
        }
    }
}
