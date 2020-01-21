package com.github.kedanachi.retrofit2.adapter.vavr;

import io.vavr.concurrent.Future;
import io.vavr.concurrent.Promise;
import retrofit2.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public class VavrFutureCallAdapterFactory extends CallAdapter.Factory {

    private final Executor executor;

    public VavrFutureCallAdapterFactory() {
        this(ForkJoinPool.commonPool());
    }

    public VavrFutureCallAdapterFactory(Executor executor) {
        this.executor = executor;
    }

    public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {
        if (getRawType(returnType) != Future.class) {
            return null;
        }
        if (!(returnType instanceof ParameterizedType)) {
            throw new IllegalStateException("Future return type must be parameterized"
                    + " as Future<Foo> or Future<? extends Foo>");
        }
        Type innerType = getParameterUpperBound(0, (ParameterizedType) returnType);

        if (getRawType(innerType) != Response.class) {
            // Generic type is not Response<T>. Use it for body-only adapter.
            return new VavrFutureCallAdapterFactory.BodyCallAdapter<>(innerType, executor);
        }

        // Generic type is Response<T>. Extract T and create the Response version of the adapter.
        if (!(innerType instanceof ParameterizedType)) {
            throw new IllegalStateException("Response must be parameterized"
                    + " as Response<Foo> or Response<? extends Foo>");
        }
        Type responseType = getParameterUpperBound(0, (ParameterizedType) innerType);
        return new VavrFutureCallAdapterFactory.ResponseCallAdapter<>(responseType, executor);
    }

    private static final class BodyCallAdapter<R> implements CallAdapter<R, Future<R>> {
        private final Type responseType;
        private final Executor executor;

        BodyCallAdapter(Type responseType, Executor executor) {
            this.responseType = responseType;
            this.executor = executor;
        }

        @Override
        public Type responseType() {
            return responseType;
        }

        @Override
        public Future<R> adapt(final Call<R> call) {
            Promise<R> promise = Promise.make(executor);
            Future<R> future = promise.future();
            call.enqueue(new Callback<R>() {
                @Override
                public void onResponse(Call<R> call, Response<R> response) {
                    if (response.isSuccessful()) {
                        promise.trySuccess(response.body());
                    } else {
                        promise.tryFailure(new HttpException(response));
                    }
                }

                @Override
                public void onFailure(Call<R> call, Throwable t) {
                    promise.tryFailure(t);
                }
            });
            future.onComplete(ignore -> {
                if (future.isCancelled()) {
                    call.cancel();
                }
            });
            return future;
        }
    }

    private static final class ResponseCallAdapter<R> implements CallAdapter<R, Future<Response<R>>> {
        private final Type responseType;
        private final Executor executor;

        ResponseCallAdapter(Type responseType, Executor executor) {
            this.responseType = responseType;
            this.executor = executor;
        }

        @Override
        public Type responseType() {
            return responseType;
        }

        @Override
        public Future<Response<R>> adapt(final Call<R> call) {
            Promise<Response<R>> promise = Promise.make(executor);
            Future<Response<R>> future = promise.future();
            call.enqueue(new Callback<R>() {
                @Override
                public void onResponse(Call<R> call, Response<R> response) {
                    promise.trySuccess(response);
                }

                @Override
                public void onFailure(Call<R> call, Throwable t) {
                    promise.tryFailure(t);
                }
            });
            future.onComplete(ignore -> {
                if (future.isCancelled()) {
                    call.cancel();
                }
            });
            return future;
        }
    }
}
