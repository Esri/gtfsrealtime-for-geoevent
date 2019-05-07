/*
  Copyright 1995-2017 Esri

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

  For additional information, contact:
  Environmental Systems Research Institute, Inc.
  Attn: Contracts Dept
  380 New York Street
  Redlands, California, USA 92373

  email: contracts@esri.com
 */

package com.esri.geoevent.adapter.gtfsrealtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.esri.core.geometry.MapGeometry;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.SpatialReference;
import com.esri.ges.adapter.AdapterDefinition;
import com.esri.ges.adapter.InboundAdapterBase;
import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.geoevent.FieldException;
import com.esri.ges.core.geoevent.FieldGroup;
import com.esri.ges.core.geoevent.GeoEvent;
import com.esri.ges.core.geoevent.GeoEventDefinition;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.manager.geoeventdefinition.GeoEventDefinitionManagerException;
import com.esri.ges.messaging.MessagingException;
import com.google.protobuf.TextFormat;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.OccupancyStatus;

public class GtfsRealtimeInboundAdapter extends InboundAdapterBase
{
  private static final BundleLogger LOGGER = BundleLoggerFactory.getLogger(GtfsRealtimeInboundAdapter.class);
  private static final String       HEADER_PROPERTY                       = "headers";
  private Boolean                   isTextFormat;
  private String[]                  headers;
  private String                    headerParams;  
  private String                    urlStr;
  private ExecutorService           executor;

  public GtfsRealtimeInboundAdapter(AdapterDefinition definition) throws ComponentException
  {
    super(definition);
  }

  /*
   * public static void main(String[] args) throws Exception { URL url = new
   * URL("URL OF YOUR GTFS-REALTIME SOURCE GOES HERE"); FeedMessage feed = FeedMessage.parseFrom(url.openStream());
   * InputStream stream = url.openStream(); for (FeedEntity entity : feed.getEntityList()) { if (entity.hasTripUpdate())
   * { System.out.println(entity.getTripUpdate()); } } }
   */
  private class GtfsToGeoEvent implements Runnable
  {
    public GtfsToGeoEvent()
    {
    }

    @Override
    public void run()
    {
      try
      {
        URL url = new URL(urlStr);
        HttpURLConnection myURLConnection = (HttpURLConnection) url.openConnection();
        if (headers != null && headers.length > 0) 
        {
          for (int i = 0; i < headers.length; i++)
          {
            String[] nameValue = headers[i].split(":");
            myURLConnection.setRequestProperty(nameValue[0], nameValue[1]);
          }
        }
        
        // myURLConnection.setRequestProperty ("Authorization", basicAuth);
        // String userCredentials = "username:password";
        // String basicAuth = "Basic " + new String(new Base64().encode(userCredentials.getBytes()));
        // myURLConnection.setRequestProperty ("Authorization", basicAuth);
        // myURLConnection.setRequestMethod("POST");
        // myURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        // myURLConnection.setRequestProperty("Content-Length", "" + postData.getBytes().length);
        // myURLConnection.setRequestProperty("Content-Language", "en-US");
        // myURLConnection.setUseCaches(false);
        // myURLConnection.setDoInput(true);
        // myURLConnection.setDoOutput(true);
        
        FeedMessage feed;
        if (isTextFormat == true)
        {
          InputStream inputStream = myURLConnection.getInputStream();
          InputStreamReader reader = new InputStreamReader(inputStream);
          FeedMessage.Builder myProtoBuilder = FeedMessage.newBuilder();
          TextFormat.merge(reader,  myProtoBuilder);
          feed = myProtoBuilder.build();
        }
        else
        {
          feed = FeedMessage.parseFrom(myURLConnection.getInputStream());          
          // feed = FeedMessage.parseFrom(url.openStream());
        }
        if (feed != null)
        {
          final long headerTimestamp = feed.getHeader().getTimestamp();
          feed.getEntityList().forEach(entity ->
            {
              // for (FeedEntity entity : feed.getEntityList())
              {
                try
        {
          if (entity.hasVehicle())
          {
            getVehiclesAndSendGeoEvents(entity, headerTimestamp);
          }

          if (entity.hasAlert())
          {
            getAlertsAndSendGeoEvents(entity, headerTimestamp);
          }

          if (entity.hasTripUpdate())
          {
            getTripUpdatesAndSendGeoEvents(entity, headerTimestamp);
          }
                }
                catch (Exception e)
                {
                  LOGGER.warn(e.getMessage());
                  LOGGER.debug(e.getMessage(), e);
                }
        } // for
            });
        }
      }
      catch (IOException e)
      {
        LOGGER.error(e.getMessage());
        LOGGER.debug(e.getMessage(), e);
      }
    }// run
  }// class

  @Override
  public void afterPropertiesSet()
  {
    super.afterPropertiesSet();
    urlStr = getProperty("url").getValueAsString();

    isTextFormat = Boolean.parseBoolean(getProperty("isTextFormat").getValueAsString());
    
    if (hasProperty(HEADER_PROPERTY))
    {
      headerParams = getProperty(HEADER_PROPERTY).getValueAsString();
      if (headerParams.isEmpty() == false)
      {
        headers = headerParams.split("[|]");        
      }
    }
    
    ExecutorService locaExecutor = executor;
    executor = null;
    if (locaExecutor != null)
    {
      try
      {
        locaExecutor.shutdownNow();
        locaExecutor.awaitTermination(3, TimeUnit.SECONDS);
      }
      catch (Exception e)
      {
        // pass
      }
    }
    executor = Executors.newFixedThreadPool(20);
  }

  @Override
  public GeoEvent adapt(ByteBuffer buffer, String channelId)
  {
    // We don't need to implement anything in here because this method will
    // never get called. It would normally be called
    // by the base class's receive() method. However, we are overriding that
    // method, and our new implementation does not call
    // the adapter's adapt() method.
    return null;
  }

  @Override
  public void receive(ByteBuffer buffer, String channelId)
  {
    // Just set the buffer pointer to its limit otherwise this will be called in a tight loop
    if (buffer.hasRemaining())
    {
      buffer.position(buffer.limit());
    }
    if (executor == null)
    {
      executor = Executors.newFixedThreadPool(20);
    }
    executor.execute(new GtfsToGeoEvent());
  }

  private GeoEvent createGeoEvent(String gedName)
    {
    GeoEvent geoEvent = null;
      AdapterDefinition def = (AdapterDefinition) definition;
      GeoEventDefinition geoDef = def.getGeoEventDefinition(gedName);

      try
      {
      // Try looking up using the GUID, sometimes this fails on startup
      if (geoEventCreator.getGeoEventDefinitionManager().getGeoEventDefinition(geoDef.getGuid()) == null)
      {
        // Didn't find the guid, lookup using name and owner
        if (geoEventCreator.getGeoEventDefinitionManager().searchGeoEventDefinition(geoDef.getName(), geoDef.getOwner()) == null)
        {
          // Still didn't find it, may have been deleted, so add it based on what was in the definition
          geoEventCreator.getGeoEventDefinitionManager().addGeoEventDefinition(geoDef);
        }
        // Get the one using the name/owner (this one has to have a valid guid)
        geoDef = geoEventCreator.getGeoEventDefinitionManager().searchGeoEventDefinition(geoDef.getName(), geoDef.getOwner());
      }
      geoEvent = geoEventCreator.create(geoDef.getGuid());
      }
      catch (MessagingException ex)
      {
        LOGGER.error("Could not create GtfsRtVehicle GeoEvent", ex);
      }
      catch (GeoEventDefinitionManagerException ex)
      {
        LOGGER.error("Could not create GtfsRtVehicle GeoEvent.", ex);
    }
    return geoEvent;
  }

  private void getVehiclesAndSendGeoEvents(FeedEntity entity, long headerTimestamp)
  {
    if (entity.hasVehicle() == false)
    {
        return;
      }
    String gedName = "GtfsRtVehicle";
    try
    {
      GeoEvent geoEvent = createGeoEvent(gedName);

      if (geoEvent != null)
      {
      geoEvent.setField("entityid", entity.getId());

      Date date = new Date(headerTimestamp);
      geoEvent.setField("headertimestamp", date);

      VehiclePosition vehiclePosition = entity.getVehicle();
      if (vehiclePosition.hasVehicle())
      {
        VehicleDescriptor vd = vehiclePosition.getVehicle();
        if (vd.hasId())
          geoEvent.setField("vehicleid", vd.getId());
        if (vd.hasLabel())
          geoEvent.setField("label", vd.getLabel());
      }

      if (vehiclePosition.hasPosition())
      {
        Position position = vehiclePosition.getPosition();
        
          double longitude = position.hasLongitude() ? position.getLongitude() : 0.0;
        if (position.hasLongitude())
          geoEvent.setField("longitude", longitude);
        
          double latitude = position.hasLatitude() ? position.getLatitude() : 0.0;
        if (position.hasLatitude())
          geoEvent.setField("latitude", latitude);

        MapGeometry point = new MapGeometry(new Point(longitude, latitude), SpatialReference.create(4326));
        geoEvent.setGeometry(point);

        if (position.hasBearing())
          geoEvent.setField("bearing", position.getBearing());

        if (position.hasSpeed())
          geoEvent.setField("speed", position.getSpeed());

        if (position.hasOdometer())
          geoEvent.setField("odometer", position.getOdometer());
      }

      if (vehiclePosition.hasTrip())
      {
        TripDescriptor td = vehiclePosition.getTrip();
        if (td.hasTripId())
          geoEvent.setField("tripid", td.getTripId());
        if (td.hasDirectionId())
          geoEvent.setField("directionid", td.getDirectionId());
        if (td.hasRouteId())
          geoEvent.setField("routeid", td.getRouteId());
        if (td.hasStartDate())
          geoEvent.setField("startdate", td.getStartDate());
        if (td.hasStartTime())
          geoEvent.setField("starttime", td.getStartTime());
        if (td.hasScheduleRelationship())
          geoEvent.setField("schedulerel", td.getScheduleRelationship().getNumber());
      }

      if (vehiclePosition.hasOccupancyStatus())
      {
        OccupancyStatus ocs = vehiclePosition.getOccupancyStatus();
        geoEvent.setField("occupancystatus", ocs.getNumber());
      }

      if (vehiclePosition.hasStopId())
      {
        geoEvent.setField("stopid", vehiclePosition.getStopId());
      }

      if (vehiclePosition.hasCurrentStopSequence())
      {
        geoEvent.setField("currentstopseq", vehiclePosition.getCurrentStopSequence());
      }

      if (vehiclePosition.hasCongestionLevel())
      {
        geoEvent.setField("congestionlev", vehiclePosition.getCongestionLevel().getNumber());
      }

      geoEventListener.receive(geoEvent);
    }
    }
    catch (FieldException e)
    {
      LOGGER.error(e.getMessage());
    }
  }

  private void getTripUpdatesAndSendGeoEvents(FeedEntity entity, long headerTimestamp)
  {
    if (entity.hasTripUpdate() == false)
    {
      return;
    }

    String gedName = "GtfsRtTripUpdate";
    try
    {
      GeoEvent geoEvent = createGeoEvent(gedName);

      if (geoEvent != null)
      {
      geoEvent.setField("entityid", entity.getId());

      TripUpdate tripUpdate = entity.getTripUpdate();

      Date date = new Date(headerTimestamp);
      geoEvent.setField("headertimestamp", date);

      if (tripUpdate.hasDelay())
      {
        geoEvent.setField("delay", tripUpdate.getDelay());
      }

      if (tripUpdate.hasTrip())
      {
        TripDescriptor td = tripUpdate.getTrip();
        if (td.hasTripId())
          geoEvent.setField("tripid", td.getTripId());
        if (td.hasDirectionId())
          geoEvent.setField("directionid", td.getDirectionId());
        if (td.hasRouteId())
          geoEvent.setField("routeid", td.getRouteId());
        if (td.hasStartDate())
          geoEvent.setField("startdate", td.getStartDate());
        if (td.hasStartTime())
          geoEvent.setField("starttime", td.getStartTime());
        if (td.hasScheduleRelationship())
          geoEvent.setField("schedulerel", td.getScheduleRelationship().getNumber());
      }

      if (tripUpdate.hasVehicle())
      {
        VehicleDescriptor vd = tripUpdate.getVehicle();
        if (vd.hasId())
          geoEvent.setField("vehicleid", vd.getId());
        if (vd.hasLabel())
          geoEvent.setField("label", vd.getLabel());
        if (vd.hasLicensePlate())
          geoEvent.setField("licenseplate", vd.getLicensePlate());
      }

      if (tripUpdate.hasTimestamp())
      {
        geoEvent.setField("timestamp", new Date(tripUpdate.getTimestamp()));
      }

      int stopTimeUpdateCount = tripUpdate.getStopTimeUpdateCount();
      geoEvent.setField("stoptimeupdatecount", stopTimeUpdateCount);
      // has to be multicardinal group field
      List<StopTimeUpdate> stuList = tripUpdate.getStopTimeUpdateList();
      List<FieldGroup> geList = new ArrayList<FieldGroup>();
      for (StopTimeUpdate stu : stuList)
      {
        FieldGroup group = geoEvent.createFieldGroup("stoptimeupdates");

        stu.hasArrival();
        StopTimeEvent ar = stu.getArrival();
        if (ar.hasDelay())
          group.setField("arrdelay", ar.getDelay());

        if (ar.hasTime())
          group.setField("arrtime", ar.getTime());

        if (ar.hasUncertainty())
          group.setField("arruncertainty", ar.getUncertainty());

        stu.hasDeparture();
        StopTimeEvent dpt = stu.getDeparture();
        if (dpt.hasDelay())
          group.setField("dptdelay", dpt.getDelay());

        if (dpt.hasTime())
          group.setField("dpttime", dpt.getTime());

        if (dpt.hasUncertainty())
          group.setField("dptuncertainty", dpt.getUncertainty());

        if (stu.hasStopId())
        {
          group.setField("stopid", stu.getStopId());
        }

        if (stu.hasStopSequence())
        {
          group.setField("currentstopseq", stu.getStopSequence());
        }

        if (stu.hasScheduleRelationship())
          group.setField("schedulerel", stu.getScheduleRelationship().getNumber());

        geList.add(group);
      }
      geoEvent.setField("stoptimeupdates", geList);

      geoEventListener.receive(geoEvent);
    }
    }
    catch (FieldException e)
    {
      LOGGER.error(e.getMessage());
    }
  }

  private void getAlertsAndSendGeoEvents(FeedEntity entity, long headerTimestamp)
  {
    if (entity.hasAlert() == false)
    {
      return;
    }

    String gedName = "GtfsRtAlert";
    try
    {
      GeoEvent geoEvent = createGeoEvent(gedName);

      if (geoEvent != null)
      {
      geoEvent.setField("entityid", entity.getId());

      Alert alert = entity.getAlert();
      Date date = new Date(headerTimestamp);
      geoEvent.setField("timestamp", date);

      int ifeCount = alert.getInformedEntityCount();
      List<EntitySelector> ifeList = alert.getInformedEntityList();
      String ifes = "";
      for (EntitySelector es : ifeList)
      {
        if (es.hasAgencyId())
        {
          ifes += es.getAgencyId();
          ifes += ":";
        }
        if (es.hasRouteId())
        {
          ifes += es.getRouteId();
          ifes += ":";
        }
        if (es.hasStopId())
        {
          ifes += es.getStopId();
        }
        ifes += ",";
      }
      ifes = ifes.substring(0, ifes.length() - 2); // get rid of the last comma
      geoEvent.setField("inf_ent_count", ifeCount);
      geoEvent.setField("inf_ent_list", ifes);

      int apCount = alert.getActivePeriodCount();
      List<TimeRange> activePeriodList = alert.getActivePeriodList();
      String aps = "";
      for (TimeRange tr : activePeriodList)
      {
        if (tr.hasStart())
        {
          long start = tr.getStart();
          aps += start;
        }
        aps += "-";
        if (tr.hasEnd())
        {
          long end = tr.getEnd();
          aps += end;
        }
        aps += ",";
      }
      aps = aps.substring(0, aps.length() - 2); // get rid of the last comma

      geoEvent.setField("act_period_count", apCount);
      geoEvent.setField("act_period_list", aps);

      if (alert.hasUrl())
      {
        geoEvent.setField("url", alert.getUrl());
      }

      if (alert.hasCause())
      {
        geoEvent.setField("cause", alert.getCause());
      }

      if (alert.hasEffect())
      {
        geoEvent.setField("effect", alert.getEffect());
      }

      if (alert.hasHeaderText())
      {
        geoEvent.setField("headertext", alert.getHeaderText());
      }

      if (alert.hasDescriptionText())
      {
        geoEvent.setField("desctext", alert.getDescriptionText());
      }

      geoEventListener.receive(geoEvent);
    }
    }
    catch (FieldException e)
    {
      LOGGER.error(e.getMessage());
    }

  }

  @Override
  public void shutdown()
  {
    if (executor != null)
    {
      executor.shutdownNow();
      try
      {
        executor.awaitTermination(10, TimeUnit.SECONDS);
      }
      catch (InterruptedException e)
      {
        // pass
      }
      executor = null;
    }
    super.shutdown();
  }
}
