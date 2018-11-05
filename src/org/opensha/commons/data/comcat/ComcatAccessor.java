package org.opensha.commons.data.comcat;

import gov.usgs.earthquake.event.EventQuery;
import gov.usgs.earthquake.event.EventWebService;
import gov.usgs.earthquake.event.Format;
import gov.usgs.earthquake.event.JsonEvent;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.ZoneId;

import org.opensha.commons.geo.GeoTools;
import org.opensha.commons.geo.Location;
import org.opensha.commons.param.Parameter;
import org.opensha.commons.param.ParameterList;
import org.opensha.commons.param.impl.StringParameter;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupList;
import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupture;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.util.zip.ZipException;


/**
 * Class for making queries to Comcat.
 * Author: Michael Barall.
 * Includes code from an earlier version written by other author(s).
 *
 *     ********** READ THIS **********
 *
 * 1. DO NOT OVERLOAD COMCAT.  Comcat is a shared resource used by people world-wide.
 * This class can send a large number of queries to Comcat in rapid succession.
 * Doing so would have a negative effect on the operation of Comcat.  So don't do that.
 *
 * 2. COMCAT IS NOT 100% RELIABLE.  Sometimes Comcat will fail to respond to a query.
 * Sometimes it will respond after a significant delay.  Any code using this class
 * must be prepared to handle these possibilities.
 *
 * 3. COMCAT HAS A QUERY MATCH LIMIT.  Comcat will not respond to a query that matches
 * more than about 150,000 earthquakes, even if you tell Comcat to only return a small
 * portion of the matching earthquakes.  Such a query has to be broken down into
 * smaller queries.  Also, be aware that such large queries impose a heavy load on
 * Comcat, and so they should only be done rarely.
 *
 * 4. DO NOT MODIFY THIS CLASS.  If you want to change something, the correct procedure
 * is to write a subclass and override whatever methods you want to change.  See
 * org.opensha.oaf.aftershockStatistics.comcat.ComcatOAFAccessor for an example.
 * Because subclasses depend on the internal design of this class remaining stable,
 * any changes carry a risk of breaking subclasses.  If you feel there is something in
 * this class that absolutely must be changed, contact the author.
 */
public class ComcatAccessor {

	// Flag is set true to write progress to System.out.
	
	protected boolean D = true;

	// The Comcat service provider.
	
	protected EventWebService service;

	// The list of HTTP status codes for the current operation.

	protected ArrayList<Integer> http_statuses;

	// HTTP status code for a locally completed operation.
	// If zero, then HTTP status must be obtained from the service provider.

	protected int local_http_status;




	/**
	 * Turn verbose mode on or off.
	 */
	public void set_verbose (boolean f_verbose) {
		D = f_verbose;
		return;
	}



	
	// Construct an object to be used for accessing Comcat.

	public ComcatAccessor () {
		this (true);
	}




	// Construct an object to be used for accessing Comcat.
	// If f_create_service is true, then create a default web service with default URL.
	// Otherwise, do not create any service, in which case the subclass must create the service.
	// This constructor is intended for use by subclasses.

	protected ComcatAccessor (boolean f_create_service) {

		// Establish verbose mode

		D = true;

		// Get the Comcat service provider, if desired

		if (f_create_service) {

			try {
				//service = new EventWebService(new URL("https://earthquake.usgs.gov/fdsnws/event/1/"));
				service = new ComcatEventWebService(new URL("https://earthquake.usgs.gov/fdsnws/event/1/"));
			} catch (MalformedURLException e) {
				ExceptionUtils.throwAsRuntimeException(e);
			}

		}

		// Set up HTTP status reporting

		http_statuses = new ArrayList<Integer>();
		local_http_status = -1;
	}




	// The number of milliseconds in a day.
	
	public static final double day_millis = 24d*60d*60d*1000d;

	// Parameter name for the place description.

	public static final String PARAM_NAME_DESCRIPTION = "description";

	// Parameter name for the event id list.

	public static final String PARAM_NAME_IDLIST = "ids";

	// Parameter name for the network.

	public static final String PARAM_NAME_NETWORK = "net";

	// Parameter name for the event code.

	public static final String PARAM_NAME_CODE = "code";

	// Maximum depth allowed in Comcat searches, in kilometers.

	public static final double COMCAT_MAX_DEPTH = 1000.0;

	// Minimum depth allowed in Comcat searches, in kilometers.

	public static final double COMCAT_MIN_DEPTH = -100.0;

	// Maximum number of events that can be requested in a single call.

	public static final int COMCAT_MAX_LIMIT = 20000;

	// Initial offset into search results.

	public static final int COMCAT_INIT_INDEX = 1;

	// Maximum number of calls permitted in a single operation.

	public static final int COMCAT_MAX_CALLS = 25;

	// Value to use for no minimum magnitude in Comcat searches.

	public static final double COMCAT_NO_MIN_MAG = -10.0;

	// Default maximum depth for Comcat searches, in kilometers.
	// This is chosen to respect the limits for both Comcat (1000.0 km) and OpenSHA (700.0).

	public static final double DEFAULT_MAX_DEPTH = 700.0;

	// Default minimum depth for Comcat searches, in kilometers.
	// This is chosen to respect the limits for both Comcat (-100.0 km) and OpenSHA (-5.0).

	public static final double DEFAULT_MIN_DEPTH = 0.0;
	



	/**
	 * Fetches an event with the given ID, e.g. "ci37166079"
	 * @param eventID = Earthquake event id.
	 * @param wrapLon = Desired longitude range: false = -180 to 180; true = 0 to 360.
	 * @return
	 * The return value can be null if the event could not be obtained.
	 * A null return means the event is either not found or deleted in Comcat.
	 * A ComcatException means that there was an error accessing Comcat.
	 * Note: This entry point always returns extended information.
	 */
	public ObsEqkRupture fetchEvent(String eventID, boolean wrapLon) {
		return fetchEvent(eventID, wrapLon, true);
	}
	



	/**
	 * Fetches an event with the given ID, e.g. "ci37166079"
	 * @param eventID = Earthquake event id.
	 * @param wrapLon = Desired longitude range: false = -180 to 180; true = 0 to 360.
	 * @param extendedInfo = True to return extended information, see eventToObsRup below.
	 * @return
	 * The return value can be null if the event could not be obtained.
	 * A null return means the event is either not found or deleted in Comcat.
	 * A ComcatException means that there was an error accessing Comcat.
	 * Note: This function is overridden in org.opensha.oaf.aftershockStatistics.comcat.ComcatOAFAccessor.
	 */
	public ObsEqkRupture fetchEvent (String eventID, boolean wrapLon, boolean extendedInfo) {

		// Initialize HTTP statuses

		http_statuses.clear();
		local_http_status = -1;

		// Set up query on event id

		EventQuery query = new EventQuery();
		query.setEventId(eventID);

		// Call Comcat to get the list of events satisfying the query

		List<JsonEvent> events = getEventsFromComcat (query);

		// If no events received, then not found

		if (events.isEmpty()) {
			return null;
		}

		// Error if more than one event was returned

		if (events.size() != 1) {
			throw new ComcatException ("ComcatAccessor: Received more than one match, count = " + events.size());
		}
		
		JsonEvent event = events.get(0);

		// Convert event to our form, treating any failure as if nothing was returned

		ObsEqkRupture rup = null;

		try {
			rup = eventToObsRup (event, wrapLon, extendedInfo);
		} catch (Exception e) {
			rup = null;
		}
		
		return rup;
	}



	
	/**
	 * Fetch all aftershocks of the given event. Returned list will not contain the mainshock
	 * even if it matches the query.
	 * @param mainshock = Mainshock.
	 * @param minDays = Start of time interval, in days after the mainshock.
	 * @param maxDays = End of time interval, in days after the mainshock.
	 * @param minDepth = Minimum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param maxDepth = Minimum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param region = Region to search.  Events not in this region are filtered out.
	 * @param wrapLon = Desired longitude range: false = -180 to 180; true = 0 to 360.
	 * @return
	 * Note: The mainshock parameter must be a return value from fetchEvent() above.
	 * Note: As a special case, if maxDays == minDays, then the end time is the current time.
	 */
	public ObsEqkRupList fetchAftershocks(ObsEqkRupture mainshock, double minDays, double maxDays,
			double minDepth, double maxDepth, ComcatRegion region, boolean wrapLon) {

		long eventTime = mainshock.getOriginTime();
		long startTime = eventTime + (long)(minDays*day_millis);
		long endTime = eventTime + (long)(maxDays*day_millis);

		String exclude_id = mainshock.getEventId();

		boolean extendedInfo = false;

		return fetchEventList (exclude_id, startTime, endTime,
								minDepth, maxDepth, region, wrapLon, extendedInfo,
								COMCAT_NO_MIN_MAG, COMCAT_MAX_LIMIT, COMCAT_MAX_CALLS);
	}



	
	/**
	 * Fetch all aftershocks of the given event. Returned list will not contain the mainshock
	 * even if it matches the query.
	 * @param mainshock = Mainshock.
	 * @param minDays = Start of time interval, in days after the mainshock.
	 * @param maxDays = End of time interval, in days after the mainshock.
	 * @param minDepth = Minimum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param maxDepth = Minimum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param region = Region to search.  Events not in this region are filtered out.
	 * @param wrapLon = Desired longitude range: false = -180 to 180; true = 0 to 360.
	 * @param minMag = Minimum magnitude, or -10.0 for no minimum.
	 * @return
	 * Note: The mainshock parameter must be a return value from fetchEvent() above.
	 * Note: As a special case, if maxDays == minDays, then the end time is the current time.
	 */
	public ObsEqkRupList fetchAftershocks(ObsEqkRupture mainshock, double minDays, double maxDays,
			double minDepth, double maxDepth, ComcatRegion region, boolean wrapLon, double minMag) {

		long eventTime = mainshock.getOriginTime();
		long startTime = eventTime + (long)(minDays*day_millis);
		long endTime = eventTime + (long)(maxDays*day_millis);

		String exclude_id = mainshock.getEventId();

		boolean extendedInfo = false;

		return fetchEventList (exclude_id, startTime, endTime,
								minDepth, maxDepth, region, wrapLon, extendedInfo,
								minMag, COMCAT_MAX_LIMIT, COMCAT_MAX_CALLS);
	}



	
	/**
	 * Fetch a list of events satisfying the given conditions.
	 * @param exclude_id = An event id to exclude from the results, or null if none.
	 * @param startTime = Start of time interval, in milliseconds after the epoch.
	 * @param endTime = End of time interval, in milliseconds after the epoch.
	 * @param minDepth = Minimum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param maxDepth = Maximum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param region = Region to search.  Events not in this region are filtered out.
	 * @param wrapLon = Desired longitude range: false = -180 to 180; true = 0 to 360.
	 * @param extendedInfo = True to return extended information, see eventToObsRup below.
	 * @param minMag = Minimum magnitude, or -10.0 for no minimum.
	 * @param limit_per_call = Maximum number of events to fetch in a single call to Comcat, or 0 for default.
	 * @param max_calls = Maximum number of calls to ComCat, or 0 for default.
	 * @return
	 * Note: As a special case, if endTime == startTime, then the end time is the current time.
	 * Note: This function is overridden in org.opensha.oaf.aftershockStatistics.comcat.ComcatOAFAccessor.
	 */
	public ObsEqkRupList fetchEventList (String exclude_id, long startTime, long endTime,
			double minDepth, double maxDepth, ComcatRegion region, boolean wrapLon, boolean extendedInfo,
			double minMag, int limit_per_call, int max_calls) {

		// Initialize HTTP statuses

		http_statuses.clear();
		local_http_status = -1;

		// Start a query

		EventQuery query = new EventQuery();

		// Insert depth into query

		if (!( minDepth < maxDepth )) {
			throw new IllegalArgumentException ("ComcatAccessor: Min depth must be less than max depth: minDepth = " + minDepth + ", maxDepth = " + maxDepth);
		}
		
		query.setMinDepth(new BigDecimal(String.format("%.3f", minDepth)));
		query.setMaxDepth(new BigDecimal(String.format("%.3f", maxDepth)));

		// Insert time into query

		long timeNow = System.currentTimeMillis();

		if (!( startTime < timeNow )) {
			throw new IllegalArgumentException ("ComcatAccessor: Start time must be less than time now: startTime = " + startTime + ", timeNow = " + timeNow);
		}

		if (!( startTime <= endTime )) {
			throw new IllegalArgumentException ("ComcatAccessor: Start time must be less than end time: startTime = " + startTime + ", endTime = " + endTime);
		}

		query.setStartTime(new Date(startTime));

		if (endTime == startTime) {
			query.setEndTime(new Date(timeNow));
		} else {
			query.setEndTime(new Date(endTime));
		}
		
		// If the region is a circle, use Comcat's circle query

		if (region.isCircular()) {
			query.setLatitude(new BigDecimal(String.format("%.5f", region.getCircleCenterLat())));
			query.setLongitude(new BigDecimal(String.format("%.5f", region.getCircleCenterLon())));
			query.setMaxRadius(new BigDecimal(String.format("%.5f", region.getCircleRadiusDeg())));
		}

		// Otherwise, use Comcat's rectangle query to search the bounding box of the region

		else {
			query.setMinLatitude(new BigDecimal(String.format("%.5f", region.getMinLat())));
			query.setMaxLatitude(new BigDecimal(String.format("%.5f", region.getMaxLat())));
			query.setMinLongitude(new BigDecimal(String.format("%.5f", region.getMinLon())));
			query.setMaxLongitude(new BigDecimal(String.format("%.5f", region.getMaxLon())));
		}

		// Set a flag to indicate if we need to do region filtering

		boolean f_region_filter = false;

		if (!( region.isRectangular() || region.isCircular() )) {
			f_region_filter = true;
		}

		// Insert minimum magnitude in the query

		if (minMag >= -9.0) {
			query.setMinMagnitude(new BigDecimal(String.format("%.3f", minMag)));
		}

		// Calculate our limit and insert it in the query

		int my_limit = limit_per_call;

		if (my_limit <= 0) {
			my_limit = COMCAT_MAX_LIMIT;
		}

		query.setLimit(my_limit);

		// Calculate our maximum number of calls

		int my_max_calls = max_calls;

		if (my_max_calls <= 0) {
			my_max_calls = COMCAT_MAX_CALLS;
		}

		// Initialize the offset but don't insert in the query yet

		int offset = COMCAT_INIT_INDEX;

		// Set up the event id filter, to remove duplicate events and our excluded event

		HashSet<String> event_filter = new HashSet<String>();

		if (exclude_id != null) {
			event_filter.add (exclude_id);
		}

		// The list of ruptures we are going to build

		ObsEqkRupList rups = new ObsEqkRupList();

		// Loop until we reach our maximum number of calls

		for (int n_call = 1; ; ++n_call) {

			// If not at start, insert offset into query

			if (offset != COMCAT_INIT_INDEX) {
				query.setOffset(offset);
			}

			// Display the query URL

			if (D) {
				try {
					System.out.println(service.getUrl(query, Format.GEOJSON));
				} catch (MalformedURLException e) {
					e.printStackTrace();
				}
			}

			// Call Comcat to get the list of events satisfying the query

			List<JsonEvent> events = getEventsFromComcat (query);

			// Display the number of events received

			int count = events.size();
			if (D) {
				System.out.println ("Count of events received = " + count);
			}

			// Loop over returned events

			int filtered_count = 0;

			for (JsonEvent event : events) {

				// Convert to our form

				ObsEqkRupture rup = null;

				try {
					rup = eventToObsRup (event, wrapLon, extendedInfo);
				} catch (Exception e) {
					rup = null;
				}

				// Skip this event if we couldn't convert it

				if (rup == null) {
					continue;
				}

				// Do region filtering if required

				if (f_region_filter) {
					if (!( region.contains(rup.getHypocenterLocation()) )) {
						continue;
					}
				}

				// Do event id filtering (must be the last filter done)

				if (!( event_filter.add (rup.getEventId()) )) {
					continue;
				}

				// Add the event to our list

				rups.add(rup);
				++filtered_count;
			}

			// Display the number of events that survived filtering

			if (D) {
				System.out.println ("Count of events after filtering = " + filtered_count);
			}

			// Advance the offset

			offset += count;

			// Stop if we didn't get all we asked for

			if (count < my_limit) {
				break;
			}

			// If reached the maximum permitted number of calls, it's an error

			if (n_call >= my_max_calls) {
				throw new ComcatException ("ComcatAccessor: Exceeded maximum number of Comcat calls in a single operation");
			}

		}

		// Display final result
		
		if (D) {
			System.out.println("Total number of events returned = " + rups.size());
		}
		
		return rups;
	}




	/**
	 * Fetch a list of events satisfying the given conditions.
	 * @param exclude_id = An event id to exclude from the results, or null if none.
	 * @param startTime = Start of time interval, in milliseconds after the epoch.
	 * @param endTime = End of time interval, in milliseconds after the epoch.
	 * @param minDepth = Minimum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param maxDepth = Maximum depth, in km.  Comcat requires a value from -100 to +1000.
	 * @param region = Region to search.  Events not in this region are filtered out.
	 * @param wrapLon = Desired longitude range: false = -180 to 180; true = 0 to 360.
	 * @param extendedInfo = True to return extended information, see eventToObsRup below.
	 * @param minMag = Minimum magnitude, or -10.0 for no minimum.
	 * @return
	 * Note: As a special case, if endTime == startTime, then the end time is the current time.
	 * Note: This function is overridden in org.opensha.oaf.aftershockStatistics.comcat.ComcatOAFAccessor.
	 */
	public ObsEqkRupList fetchEventList (String exclude_id, long startTime, long endTime,
			double minDepth, double maxDepth, ComcatRegion region, boolean wrapLon, boolean extendedInfo,
			double minMag) {

		return fetchEventList (exclude_id, startTime, endTime,
			minDepth, maxDepth, region, wrapLon, extendedInfo,
			minMag, COMCAT_MAX_LIMIT, COMCAT_MAX_CALLS);
	}




	// Convert a JsonEvent into an ObsEqkRupture.
	// If wrapLon is false, longitudes range from -180 to +180.
	// If wrapLon is true, longitudes range from 0 to +360.
	// If extendedInfo is true, extended information is added to the ObsEqkRupture,
	//  which presently is the place description, list of ids, network, and event code.
	// If the conversion could not be performed, then the function either returns null
	//  or throws an exception.  Note that the event.getXXXXX functions can return null,
	//  which will lead to NullPointerException being thrown.
	// Note: A subclass can override this method to add more extended information.
	
	protected ObsEqkRupture eventToObsRup(JsonEvent event, boolean wrapLon, boolean extendedInfo) {
		double lat = event.getLatitude().doubleValue();
		double lon = event.getLongitude().doubleValue();

		GeoTools.validateLon(lon);
		if (wrapLon && lon < 0.0) {
			lon += 360.0;
			GeoTools.validateLon(lon);
		}

		double dep = event.getDepth().doubleValue();
		if (dep < 0.0) {
			// some regional networks can report negative depths, but the definition of what they're relative to can vary between
			// networks (see http://earthquake.usgs.gov/data/comcat/data-eventterms.php#depth) so we decided to just discard any
			// negative depths rather than try to correct with a DEM (which may be inconsistant with the networks). More discussion
			// in e-mail thread 2/8-9/17 entitled "ComCat depths in OAF app"
			dep = 0.0;
		}
		Location hypo = new Location(lat, lon, dep);

		double mag=0.0;
		try{
			mag = event.getMag().doubleValue();
		}catch(Exception e){
			//System.out.println(event.toString());
			return null;
		}

		String event_id = event.getEventId().toString();
		if (event_id == null || event_id.isEmpty()) {
			return null;	// this ensures returned events always have an event ID
		}

		ObsEqkRupture rup = new ObsEqkRupture(event_id, event.getTime().getTime(), hypo, mag);
		
		if (extendedInfo) {
			// adds the place description ("10km from wherever"). Needed for ETAS_AftershockStatistics forecast document -NVDE 
			rup.addParameter(new StringParameter(PARAM_NAME_DESCRIPTION, event.getPlace()));
			// adds the event id list, which can be used to resolve duplicates
			rup.addParameter(new StringParameter(PARAM_NAME_IDLIST, event.getIds()));
			// adds the seismic network, which is needed for reporting to PDL
			rup.addParameter(new StringParameter(PARAM_NAME_NETWORK, event.getNet()));
			// adds the event code, which is needed for reporting to PDL
			rup.addParameter(new StringParameter(PARAM_NAME_CODE, event.getCode()));
		}
		
		return rup;
	}




	// Get the HTTP status code from the last operation, or -1 if unknown, or -2 if unable to connect.

	public int get_http_status_code () {
		if (local_http_status != 0) {
			return local_http_status;
		}
		if (service instanceof ComcatEventWebService) {
			return ((ComcatEventWebService)service).get_http_status_code();
		}
		return -1;
	}


	// Get the number of stored HTTP statuses available, for the last multi-event fetch.

	public int get_http_status_count () {
		return http_statuses.size();
	}


	// Get the i-th stored HTTP status, for the last multi-event fetch.

	public int get_http_status_code (int i) {
		return http_statuses.get(i).intValue();
	}
	



	/**
	 * Send a query to Comcat and return the matching list of events.
	 * @param query = Query to perform.
	 * @return
	 * Returns a list of events matching the query.
	 * If nothing matches the query, returns an empty list.
	 * The return is never null.
	 * If there is an error, throws ComcatException.
	 * The operation's HTTP status is added to the list of status codes.
	 *
	 * Implementation note:
	 * With Comcat, it is necessary to examine the HTTP status code to accurately tell
	 * the difference between an I/O error and a query that returns no data.  The
	 * ComcatEventWebService provider attempts to do so.  If HTTP status is not available
	 * (because the provider is not ComcatEventWebService, or the protocol stack did not
	 * obtain the HTTP status), this function makes an attempt to tell the difference,
	 * but the attempt does not succeed in all circumstances.
	 */
	protected List<JsonEvent> getEventsFromComcat (EventQuery query) {

		List<JsonEvent> events = null;
		local_http_status = 0;

		try {
			events = service.getEvents(query);

		} catch (SocketTimeoutException e) {
			// This exception (subclass of IOException) indicates an I/O error.
			http_statuses.add (new Integer(get_http_status_code()));
			throw new ComcatException ("ComcatAccessor: I/O error (SocketTimeoutException) while accessing Comcat", e);

		} catch (UnknownServiceException e) {
			// This exception (subclass of IOException) indicates an I/O error.
			http_statuses.add (new Integer(get_http_status_code()));
			throw new ComcatException ("ComcatAccessor: I/O error (UnknownServiceException) while accessing Comcat", e);

		} catch (ZipException e) {
			// This exception (subclass of IOException) indicates a data error.
			http_statuses.add (new Integer(get_http_status_code()));
			throw new ComcatException ("ComcatAccessor: Data error (ZipException) while accessing Comcat", e);

		} catch (FileNotFoundException e) {
			// If the HTTP status is unknown, then we don't know if this is error or not-found.
			// EventWebService typically throws this exception when an eventID is not found (in response
			// to Comcat HTTP status 404), so we treat it as not-found if the HTTP status is unknown.
			if (get_http_status_code() == -1) {
				events = new ArrayList<JsonEvent>();
			}
			// Otherwise it's an I/O error
			else {
				http_statuses.add (new Integer(get_http_status_code()));
				throw new ComcatException ("ComcatAccessor: I/O error (FileNotFoundException) while accessing Comcat", e);
			}

		} catch (IOException e) {
			// If the HTTP status is unknown, then we don't know if this is error or not-found.
			// EventWebService typically throws this exception when an eventID refers to a deleted event
			// (in response to Comcat HTTP status 409), but we nonetheless treat it as an I/O error.
			http_statuses.add (new Integer(get_http_status_code()));
			throw new ComcatException ("ComcatAccessor: I/O error (IOException) while accessing Comcat", e);

		} catch (Exception e) {
			// An exception not an I/O exception probably indicates bad data received.
			http_statuses.add (new Integer(get_http_status_code()));
			throw new ComcatException ("ComcatAccessor: Data error (Exception) while accessing Comcat", e);
		}

		// Add the HTTP status to the list

		http_statuses.add (new Integer(get_http_status_code()));

		// The event list should not be null, but if it is, replace it with an empty List

		if (events == null) {
			events = new ArrayList<JsonEvent>();
		}

		// Return the list of events

		return events;
	}




	// Option codes for extendedInfoToMap.

	public static final int EITMOPT_NO_CHANGE = 0;			// Make no changes, include null and empty strings in map
	public static final int EITMOPT_OMIT_NULL = 1;			// Omit null strings from the map, but keep empty strings
	public static final int EITMOPT_OMIT_EMPTY = 2;			// Omit empty strings from the map, but keep null strings
	public static final int EITMOPT_OMIT_NULL_EMPTY = 3;	// Omit both null strings and empty strings from the map
	public static final int EITMOPT_NULL_TO_EMPTY = 4;		// Convert null strings to empty strings
	public static final int EITMOPT_EMPTY_TO_NULL = 5;		// Convert empty strings to null strings


	/**
	 * Extract the extended info from a ObsEqkRupture, and put all the values into a Map.
	 * @param rup = The ObsEqkRupture to examine.
	 * @param option = An option code, as listed above.
	 * @return
	 * Returns a Map whose keys are the the names of parameters (PARAM_NAME_XXXXX as
	 * defined above), and whose values are the strings returned by Comcat.
	 * Any non-string parameters are converted to strings.
	 */
	public static Map<String, String> extendedInfoToMap (ObsEqkRupture rup, int option) {
		HashMap<String, String> eimap = new HashMap<String, String>();

		// Loop over parameters containing extended info

		ListIterator<Parameter<?>> iter = rup.getAddedParametersIterator();
		if (iter != null) {
			while (iter.hasNext()) {
				Parameter<?> param = iter.next();

				// Get the name and value of the parameter

				String key = param.getName();
				String value = null;
				Object o = param.getValue();
				if (o != null) {
					value = o.toString();
				}

				// Handle null strings

				if (value == null) {
					switch (option) {

					case EITMOPT_OMIT_NULL:
					case EITMOPT_OMIT_NULL_EMPTY:
						continue;

					case EITMOPT_NULL_TO_EMPTY:
						value = "";
						break;
					}
				}

				// Handle empty strings

				else if (value.isEmpty()) {
					switch (option) {

					case EITMOPT_OMIT_EMPTY:
					case EITMOPT_OMIT_NULL_EMPTY:
						continue;

					case EITMOPT_EMPTY_TO_NULL:
						value = null;
						break;
					}
				}

				// Add to the map

				eimap.put (key, value);
			}
		}

		return eimap;
	}




	/**
	 * Convert an id list received from Comcat into a list of strings, each containing a single id.
	 * @param ids = The ids received from Comcat.  Can be null or empty if none received.
	 * @param preferred_id = A preferred id.  Can be null or empty if none desired.
	 * @return
	 * Returns a List of strings, each of which is an event id.
	 * The ids received from Comcat are a comma-separated list of event ids, with commas at
	 * the beginning and end of the list, for example: ",us1000edv8,hv70203677,".
	 * It is not clear if the ordering of ids in the list has any significance.
	 * This function treats the initial and final commas as optional, and allows spaces before
	 * and after a comma (which are removed).
	 * If preferred_id is non-null and non-empty, then preferred_id appears as the first
	 * item in the list (whether or not it also appears in ids).
	 * Except for preferred_id, the event ids are listed in the same order they appear in ids.
	 * It is guaranteed that the returned list contains no duplicates, even in the unlikely
	 * (maybe impossible) event that Comcat returns a list containing a duplicate.
	 */
	public static List<String> idsToList (String ids, String preferred_id) {
		ArrayList<String> idlist = new ArrayList<String>();
		HashSet<String> idset = new HashSet<String>();

		// If there is a preferred id, make it the first element in the list

		if (preferred_id != null) {
			if (!( preferred_id.isEmpty() )) {
				idset.add (preferred_id);
				idlist.add (preferred_id);
			}
		}

		// If we have a list of ids ...

		if (ids != null) {

			// Break the ids into individual events

			int n = ids.length();
			int begin = 0;		// Beginning of the current substring

			while (begin < n) {

				// The end of the current substring is the next comma, or the end of the string

				int end = ids.indexOf (",", begin);
				if (end < 0) {
					end = n;
				}

				// The event id is the current substring, with leading and trailing spaces removed

				String id = ids.substring (begin, end).trim();

				// If it's non-empty ...

				if (!( id.isEmpty() )) {
				
					// If it has not been seen before ...

					if (idset.add(id)) {
					
						// Add it to the list of ids

						idlist.add (id);
					}
				}
			
				// Advance to the next candidate substring, which begins after the comma

				begin = end + 1;
			}
		}

		// Return the List

		return idlist;
	}




	/**
	 * Convert a rupture to a string.
	 * @param rup = The ObsEqkRupture to convert.
	 * @return
	 * Returns string describing the rupture contents.
	 * This function is mainly for testing.
	 */
	public static String rupToString (ObsEqkRupture rup) {
		StringBuilder result = new StringBuilder();

		String rup_event_id = rup.getEventId();
		long rup_time = rup.getOriginTime();
		double rup_mag = rup.getMag();
		Location hypo = rup.getHypocenterLocation();
		double rup_lat = hypo.getLatitude();
		double rup_lon = hypo.getLongitude();
		double rup_depth = hypo.getDepth();

		String rup_time_string = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
			.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(rup_time));

		result.append ("ObsEqkRupture:" + "\n");
		result.append ("rup_event_id = " + rup_event_id + "\n");
		result.append ("rup_time = " + rup_time + " (" + rup_time_string + ")" + "\n");
		result.append ("rup_mag = " + rup_mag + "\n");
		result.append ("rup_lat = " + rup_lat + "\n");
		result.append ("rup_lon = " + rup_lon + "\n");
		result.append ("rup_depth = " + rup_depth + "\n");

		ListIterator<Parameter<?>> iter = rup.getAddedParametersIterator();
		if (iter != null) {
			while (iter.hasNext()) {
				Parameter<?> param = iter.next();
				result.append (param.getName() + " = " + param.getValue() + "\n");
			}
		}

		return result.toString();
	}

}
