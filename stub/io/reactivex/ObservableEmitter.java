package io.reactivex;
public interface ObservableEmitter<T> {
    void onNext(T value);
    void onError(Throwable error);
    void onComplete();
}
