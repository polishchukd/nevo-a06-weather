package com.ca.hmi.weather.sdk.api;
import com.ca.hmi.weather.sdk.bean.LocationWeather;
import com.ca.hmi.weather.sdk.bean.PredictWeatherData;
import com.ca.hmi.weather.sdk.bean.VehicleLocation;
import com.ca.hmi.weather.sdk.bean.WeatherAlert;
import com.incall.apps.component.okhttp.bean.BaseNewSO;
import io.reactivex.Observable;
public interface IWeatherAPI {
    Observable<BaseNewSO<LocationWeather>> getLocationWeatherDetail(VehicleLocation req);
    Observable<BaseNewSO<PredictWeatherData>> getPredictHoursWeather(VehicleLocation req);
    Observable<BaseNewSO<WeatherAlert>> getWeatherAlert(VehicleLocation req);
}
