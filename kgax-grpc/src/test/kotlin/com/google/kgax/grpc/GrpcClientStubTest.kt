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

package com.google.kgax.grpc

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.google.longrunning.Operation
import com.google.protobuf.Int32Value
import com.google.protobuf.StringValue
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.stub.AbstractStub
import io.grpc.stub.StreamObserver
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.fail

class GrpcClientStubTest {

    fun StringValue(value: String): StringValue = StringValue.newBuilder().setValue(value).build()
    fun Int32Value(value: Int): Int32Value = Int32Value.newBuilder().setValue(value).build()

    @Test
    fun `ClientCallOptions remembers metadata`() {
        val options = ClientCallOptions.Builder()
        options.withMetadata("foo", listOf("a", "b"))
        options.withMetadata("bar", listOf("1", "2"))
        options.withMetadata("foo", listOf("one"))

        assertThat(options.requestMetadata).containsExactlyEntriesIn(
            mapOf("foo" to listOf("one"), "bar" to listOf("1", "2"))
        )
    }

    @Test
    fun `ClientCallOptions forgets metadata`() {
        val options = ClientCallOptions.Builder()
        options.withMetadata("foo", listOf("a", "b"))
        options.withMetadata("bar", listOf("1", "2"))
        options.withoutMetadata("foo")

        assertThat(options.requestMetadata).containsExactlyEntriesIn(
            mapOf("bar" to listOf("1", "2"))
        )
    }

    @Test
    fun `Can do a blocking call`() {
        val stub: TestStub = createTestStubMock()

        val call = GrpcClientStub(stub, ClientCallOptions())
        val result = call.executeBlocking { arg ->
            assertThat(arg).isEqualTo(stub)
            StringValue("hey there")
        }
        assertThat(result.body.value).isEqualTo("hey there")
    }

    @Test
    fun `Can do a blocking call with metadata`() {
        val stub: TestStub = createTestStubMock()
        val options = clientCallOptions {
            withMetadata("one", listOf("two", "three"))
        }

        val call = GrpcClientStub(stub, options)
        val result = call.executeBlocking { arg ->
            verify(arg, times(2)).withInterceptors(any())
            StringValue("hi")
        }
        assertThat(result.body.value).isEqualTo("hi")
    }

    @Test
    fun `Can do a future call`() {
        val stub: TestStub = createTestStubMock()
        val future = SettableFuture.create<StringValue>()
        future.set(StringValue("hi"))

        val call = GrpcClientStub(stub, ClientCallOptions())
        val result = call.executeFuture { arg ->
            assertThat(arg).isEqualTo(stub)
            future
        }
        assertThat(result.get().body.value).isEqualTo("hi")
    }

    @Test
    fun `Can do a long running call`() {
        val stub: TestStub = createTestStubMock()
        val operation = Operation.newBuilder()
            .setName("the op")
            .setDone(true)
            .build()

        val call = GrpcClientStub(stub, ClientCallOptions())
        val result = call.executeLongRunning(StringValue::class.java) { arg ->
            assertThat(arg).isEqualTo(stub)
            val operationFuture = SettableFuture.create<Operation>()
            operationFuture.set(operation)
            operationFuture
        }
        result.get()
        assertThat(result.operation).isEqualTo(operation)
    }

    @Test
    fun `Can do a streaming call`() {
        val stub: TestStub = createTestStubMock()
        val inStream: StreamObserver<Int32Value> = mock()
        val exception: RuntimeException = mock()

        // capture output stream
        val call = GrpcClientStub(stub, ClientCallOptions())
        var outStream: StreamObserver<StringValue>? = null
        fun method(outs: StreamObserver<StringValue>): StreamObserver<Int32Value> {
            outStream = outs
            return inStream
        }

        val result = call.executeStreaming { arg ->
            assertThat(arg).isEqualTo(stub)
            ::method
        }

        val responses = mutableListOf<String>()
        val exceptions = mutableListOf<Throwable>()
        var complete = false

        result.responses.onNext = { responses.add(it.value) }
        result.responses.onError = { exceptions.add(it) }
        result.responses.onCompleted = { complete = true }
        result.start()

        result.requests.send(Int32Value(1))
        result.requests.send(Int32Value(2))

        // fake output from server
        outStream?.onNext(StringValue("one"))
        outStream?.onNext(StringValue("two"))
        outStream?.onError(exception)
        outStream?.onCompleted()

        verify(inStream).onNext(Int32Value(1))
        verify(inStream).onNext(Int32Value(2))
        assertThat(responses).containsExactly("one", "two")
        assertThat(exceptions).containsExactly(exception)
        assertThat(complete).isTrue()
    }

    @Test(expected = kotlin.UninitializedPropertyAccessException::class)
    fun `Throws when a streaming call is not started`() {
        val stub: TestStub = createTestStubMock()
        val inStream: StreamObserver<Int32Value> = mock()

        // capture output stream
        val call = GrpcClientStub(stub, ClientCallOptions())
        fun method(outs: StreamObserver<StringValue>): StreamObserver<Int32Value> {
            return inStream
        }

        val result = call.executeStreaming { arg ->
            assertThat(arg).isEqualTo(stub)
            ::method
        }

        result.requests.send(Int32Value(1))
    }

    @Test
    fun `Can do a client streaming call`() {
        listOf(null, RuntimeException("failed")).forEach { ex ->
            val stub: TestStub = createTestStubMock()
            val inStream: StreamObserver<Int32Value> = mock()

            // capture output stream
            val call = GrpcClientStub(stub, ClientCallOptions())
            var outStream: StreamObserver<StringValue>? = null
            fun method(outs: StreamObserver<StringValue>): StreamObserver<Int32Value> {
                outStream = outs
                return inStream
            }

            val result = call.executeClientStreaming { arg ->
                assertThat(arg).isEqualTo(stub)
                ::method
            }

            result.start()
            result.requests.send(Int32Value.newBuilder().setValue(10).build())
            result.requests.send(Int32Value.newBuilder().setValue(20).build())

            // fake output from server
            if (ex != null) {
                outStream?.onError(ex)
            } else {
                outStream?.onNext(StringValue("abc"))
            }
            outStream?.onCompleted()

            verify(inStream).onNext(Int32Value(10))
            verify(inStream).onNext(Int32Value(20))
            if (ex != null) {
                assertFailsWith<ExecutionException>("failed") { result.response.get() }
            } else {
                assertThat(result.response.get().value).isEqualTo("abc")
            }
        }
    }

    @Test(expected = kotlin.UninitializedPropertyAccessException::class)
    fun `Throws when a client streaming call is not started`() {
        listOf(null, RuntimeException("failed")).forEach { ex ->
            val stub: TestStub = createTestStubMock()
            val inStream: StreamObserver<Int32Value> = mock()

            // capture output stream
            val call = GrpcClientStub(stub, ClientCallOptions())
            fun method(outs: StreamObserver<StringValue>): StreamObserver<Int32Value> {
                return inStream
            }

            val result = call.executeClientStreaming { arg ->
                assertThat(arg).isEqualTo(stub)
                ::method
            }

            result.requests.send(Int32Value.newBuilder().setValue(10).build())
            result.requests.send(Int32Value.newBuilder().setValue(20).build())
        }
    }

    @Test
    fun `Can do a server streaming call`() {
        val stub: TestStub = createTestStubMock()
        val exception: RuntimeException = mock()

        // capture output stream
        val call = GrpcClientStub(stub, ClientCallOptions())
        var outStream: StreamObserver<StringValue>? = null
        val result = call.executeServerStreaming { it, observer: StreamObserver<StringValue> ->
            assertThat(it).isEqualTo(stub)
            outStream = observer
        }

        val responses = mutableListOf<String>()
        val exceptions = mutableListOf<Throwable>()
        var complete = false

        result.start {
            onNext = { responses.add(it.value) }
            onError = { exceptions.add(it) }
            onCompleted = { complete = true }
        }

        // fake output from server
        outStream?.onNext(StringValue("one"))
        outStream?.onNext(StringValue("two"))
        outStream?.onError(exception)
        outStream?.onCompleted()

        assertThat(responses).containsExactly("one", "two")
        assertThat(exceptions).containsExactly(exception)
        assertThat(complete).isTrue()
    }

    @Test
    fun `Has no responses when a server streaming call is not started`() {
        val stub: TestStub = createTestStubMock()
        val exception: RuntimeException = mock()

        // capture output stream
        val call = GrpcClientStub(stub, ClientCallOptions())
        var outStream: StreamObserver<StringValue>? = null
        val result = call.executeServerStreaming { it, observer: StreamObserver<StringValue> ->
            assertThat(it).isEqualTo(stub)
            outStream = observer
        }

        val responses = mutableListOf<String>()
        val exceptions = mutableListOf<Throwable>()
        var complete = false

        // forget to call start
        result.responses.onNext = { responses.add(it.value) }
        result.responses.onError = { exceptions.add(it) }
        result.responses.onCompleted = { complete = true }

        // fake output from server
        outStream?.onNext(StringValue("one"))
        outStream?.onNext(StringValue("two"))
        outStream?.onError(exception)
        outStream?.onCompleted()

        assertThat(responses).isEmpty()
        assertThat(exceptions).isEmpty()
        assertThat(complete).isFalse()
    }

    @Test
    fun `can be prepared`() {
        val stub = createTestStubMock()
        val otherStub = stub.prepare {
            withMetadata("a", listOf("one", "two"))
            withMetadata("other", listOf())
        }

        assertThat(stub).isNotEqualTo(otherStub)
        assertThat(otherStub.options.requestMetadata)
            .containsExactlyEntriesIn(
                mapOf(
                    "a" to listOf("one", "two"),
                    "other" to listOf()
                )
            )
    }

    @Test
    fun `can handle successful callbacks`() {
        val call = SettableFuture.create<CallResult<StringValue>>()
        var successValue: String? = null
        var errorValue: Throwable? = null

        call.on(MoreExecutors.directExecutor()) {
            success = { successValue = it.body.value }
            error = { errorValue = it }
        }
        call.set(CallResult(StringValue("hey"), ResponseMetadata()))

        assertThat(successValue).isEqualTo("hey")
        assertThat(errorValue).isNull()
    }

    @Test
    fun `can handle error callbacks`() {
        val call = SettableFuture.create<CallResult<StringValue>>()
        var successValue: String? = null
        var errorValue: Throwable? = null

        call.on(MoreExecutors.directExecutor()) {
            success = { successValue = it.body.value }
            error = { errorValue = it }
        }
        call.setException(IllegalStateException())

        assertThat(successValue).isNull()
        assertThat(errorValue).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `can skip successful callbacks`() {
        val call = SettableFuture.create<CallResult<StringValue>>()

        call.on(MoreExecutors.directExecutor()) {
            success = { fail("success was called") }
            error = { fail("error was called") }
            ignoreIf = { true }
        }
        call.set(CallResult(StringValue("hey"), ResponseMetadata()))
    }

    @Test
    fun `can skip successful callbacks only`() {
        val call = SettableFuture.create<CallResult<StringValue>>()

        call.on(MoreExecutors.directExecutor()) {
            success = { fail("success was called") }
            error = { fail("error was called") }
            ignoreResultIf = { true }
        }
        call.set(CallResult(StringValue("you"), ResponseMetadata()))
    }

    @Test
    fun `can skip successful callbacks without confusion`() {
        val call = SettableFuture.create<CallResult<StringValue>>()
        var value: String? = null

        call.on(MoreExecutors.directExecutor()) {
            success = { value = it.body.value }
            error = { fail("error was called") }
            ignoreErrorIf = { true }
        }
        call.set(CallResult(StringValue("you"), ResponseMetadata()))

        assertThat(value).isEqualTo("you")
    }

    @Test
    fun `can skip error callbacks`() {
        val call = SettableFuture.create<CallResult<StringValue>>()

        call.on(MoreExecutors.directExecutor()) {
            success = { fail("success was called") }
            error = { fail("error was called") }
            ignoreIf = { true }
        }
        call.setException(RuntimeException())
    }

    @Test
    fun `can skip error callbacks only`() {
        val call = SettableFuture.create<CallResult<StringValue>>()

        call.on(MoreExecutors.directExecutor()) {
            success = { fail("success was called") }
            error = { fail("error was called") }
            ignoreErrorIf = { true }
        }
        call.setException(RuntimeException())
    }

    @Test
    fun `can skip error callbacks without confusion`() {
        val call = SettableFuture.create<CallResult<StringValue>>()
        var err: Throwable? = null

        call.on(MoreExecutors.directExecutor()) {
            success = { fail("success was called") }
            error = { err = it }
            ignoreResultIf = { true }
        }
        call.setException(RuntimeException())

        assertThat(err).isInstanceOf(RuntimeException::class.java)
    }

    private fun createTestStubMock(): TestStub {
        val stub: TestStub = mock()
        whenever(stub.channel).thenReturn(mock())
        whenever(stub.withInterceptors(any())).thenReturn(stub)
        whenever(stub.withCallCredentials(any())).thenReturn(stub)
        whenever(stub.withOption(any(), any<Any>())).thenReturn(stub)
        return stub
    }

    private class TestStub(channel: Channel, options: CallOptions) :
        AbstractStub<TestStub>(channel, options) {

        override fun build(channel: Channel, options: CallOptions): TestStub {
            return TestStub(channel, options)
        }
    }
}
