package com.incall.apps.component.okhttp.bean;
public class BaseNewSO<T> {
    public Integer code;
    public T data;
    public String message;
    public boolean success;
    public boolean isOk() { return code != null && code.intValue() == 0; }
}
