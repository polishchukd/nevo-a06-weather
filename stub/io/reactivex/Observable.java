package io.reactivex;
public abstract class Observable<T> {
    public static <T> Observable<T> create(ObservableOnSubscribe<T> source) { throw new Error("stub"); }
    public static <T> Observable<T> error(Throwable e) { throw new Error("stub"); }
}
