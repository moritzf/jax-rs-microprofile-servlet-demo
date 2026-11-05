package de.osmtrailzustand;

import jakarta.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "weather")
@Path("/v1/forecast")
@Produces("application/json")
public interface WeatherClient {
    @GET
    JsonObject forecast(@QueryParam("latitude") double latitude,
                        @QueryParam("longitude") double longitude,
                        @QueryParam("hourly") String variables,
                        @QueryParam("forecast_hours") int hours,
                        @QueryParam("timeformat") String timeFormat);
}
