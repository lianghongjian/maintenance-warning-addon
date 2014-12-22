package org.exoplatform.addon.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.RuntimeDelegate;

import org.exoplatform.calendar.service.Calendar;
import org.exoplatform.calendar.service.CalendarEvent;
import org.exoplatform.calendar.service.CalendarService;
import org.exoplatform.calendar.service.EventQuery;
import org.exoplatform.calendar.service.GroupCalendarData;
import org.exoplatform.calendar.service.Reminder;
import org.exoplatform.calendar.service.Utils;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.impl.RuntimeDelegateImpl;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.joda.time.DateTimeZone;

@Path("/reminderservice")
@RolesAllowed("users")
public class ReminderRestService implements ResourceContainer {
	private static final Log log = ExoLogger.getLogger(ReminderRestService.class.getName());	
	//check after and before 1 hour
	private static final int HOUR_BEFORE = 1;
	//default is 5 minutes
	private static long DEFAULT_REPEAT_INTERVAL_MINUTE = 5*60*1000;	
	
	  private static final CacheControl cacheControl;
	  static {
	    RuntimeDelegate.setInstance(new RuntimeDelegateImpl());
	    cacheControl = new CacheControl();
	    cacheControl.setNoCache(true);
	    cacheControl.setNoStore(true);
	  }
	

	public ReminderRestService() {
	}
	
	
	@GET
	@Path("call")
	@Produces("application/json")
	@RolesAllowed("users")
	public Response callpopup(@Context SecurityContext sc, @Context UriInfo uriInfo) throws Exception {
		List<MessageReminder> listCommentMessages = new ArrayList<ReminderRestService.MessageReminder>();
		String username = getUserId(sc, uriInfo);	

		Calendar cal = new Calendar();		
	    CalendarService calendarService = (CalendarService)PortalContainer.getInstance().getComponentInstance(CalendarService.class);	    

	    // get current time base on timezone
	    DateTimeZone timeZone = DateTimeZone.forID(cal.getTimeZone());
	    java.util.Calendar timeCurrent = java.util.Calendar.getInstance(timeZone.toTimeZone());
	    
	    //set time after and before 1 hours
	    timeCurrent.set(java.util.Calendar.HOUR_OF_DAY, timeCurrent.get(java.util.Calendar.HOUR_OF_DAY) - HOUR_BEFORE);
	    Date  timeCurrentBefore1Hour = timeCurrent.getTime(); 
	    timeCurrent.set(java.util.Calendar.HOUR_OF_DAY, timeCurrent.get(java.util.Calendar.HOUR_OF_DAY) + 2*HOUR_BEFORE );
	    Date timeCurrentAfter1Hour = timeCurrent.getTime();
	    //set current time to normal
	    timeCurrent.set(java.util.Calendar.HOUR_OF_DAY, timeCurrent.get(java.util.Calendar.HOUR_OF_DAY) - HOUR_BEFORE );
	    
	    EventQuery eventQuery = new EventQuery();
	    String idCalendarAdmin[] = new String[] {""};	    
	    List<GroupCalendarData> groupCalendarAdminList = calendarService.getGroupCalendars(new String[]{"/platform/users"}, true, username);
	    for (GroupCalendarData group : groupCalendarAdminList ){	    	
	    	for (Calendar itemCalendar : group.getCalendars())
	    	{
	    		// if calendar name Maintenance
	    		if(itemCalendar.getName().equals(ReminderServiceImpl.nameCalendarMaintenance)){
	    		idCalendarAdmin[idCalendarAdmin.length-1]=itemCalendar.getId();	    
	    		}
	    	}
	    }
	    	    
	    eventQuery.setCalendarId(idCalendarAdmin);
	    
	    List<CalendarEvent> events = calendarService.getPublicEvents(eventQuery);
	    for (CalendarEvent baseResultEvent: events){    
			MessageReminder msgReminder = new MessageReminder();	
	    	
	    	//if calendar event repeat, build a series of calendar linked to the first calendar, then check if current calendar (+-1h) have Maintenance event
	    	if (Utils.isRepeatEvent(baseResultEvent)) {
	    		Collection<CalendarEvent> calendarCollection = calendarService.buildSeries(baseResultEvent, timeCurrentBefore1Hour, timeCurrentAfter1Hour, username);
	    		Iterator<CalendarEvent> iteratorCalendar = calendarCollection.iterator();
	    		
	    		log.info("=======REPEAT EVENT=======");
	    		while(iteratorCalendar.hasNext()){
	    			CalendarEvent calendarRepeat = iteratorCalendar.next();
	    	    	List<Reminder> listReminder = calendarRepeat.getReminders();
	    	    	for (Reminder reminderItem : listReminder){
	    	    		reminderItem.setFromDateTime(baseResultEvent.getFromDateTime());
	    	    		displayWarningReminderPopup(reminderItem, timeCurrent, msgReminder);	
	    	    	}
	    		}
	    	}
	    	/*if not repeat, check only event +-1h*/		    	
	    	else{		    		
	    		log.info("=======NOT REPEAT EVENT=======");
    	    	List<Reminder> listReminder = baseResultEvent.getReminders();
    	    	for (Reminder reminderItem : listReminder){	    	    		
    	    		reminderItem.setFromDateTime(baseResultEvent.getFromDateTime());
    	    		displayWarningReminderPopup(reminderItem, timeCurrent, msgReminder);	    	    		
    	    	}		    		
	    	}
	    	listCommentMessages.add(msgReminder);
	    }
	    
		return Response.ok(listCommentMessages , MediaType.APPLICATION_JSON).cacheControl(cacheControl).build();
	}
	
	/**
	 * display warning reminder popup if time alarm before < current time < time reminder
	 * then repeat after intervall minute
	 * @param reminderItem
	 * @param timeCurrent
	 */
	private static void displayWarningReminderPopup(Reminder reminderItem, java.util.Calendar timeCurrent, MessageReminder msgReminder){
		if (reminderItem.getReminderType().equals(Reminder.TYPE_POPUP)){ 
			long minuteBeforeEventStarts = reminderItem.getAlarmBefore() *60 *1000;	    			
			Date timeBeforeEventStarts = new Date( reminderItem.getFromDateTime().getTime() - minuteBeforeEventStarts);
			
			Boolean before = timeCurrent.getTime().after(timeBeforeEventStarts);
			Boolean after = timeCurrent.getTime().before(reminderItem.getFromDateTime());		
			//if time alarm before < current time < time reminder, we display popup
			if (before && after){    	    				
//				log.info("======= DISPLAY REMINDER POPUP=======");
//				log.info(" at "+reminderItem.getFromDateTime() +" before: " + reminderItem.getAlarmBefore()+"m "+reminderItem.getDescription() );
				//repeat intervall to display popup
				DEFAULT_REPEAT_INTERVAL_MINUTE = reminderItem.getRepeatInterval();
				if (reminderItem.getFromDateTime() != null){
				msgReminder.setFromDate(reminderItem.getFromDateTime());
				msgReminder.setToDate(new Date());
				msgReminder.setDescription(reminderItem.getDescription());
//				log.info(reminderItem.getDescription());
				msgReminder.setSummary(reminderItem.getSummary());
				msgReminder.setRepeatIntervalMinute(reminderItem.getRepeatInterval());
				}
			}
			else{
				//if no reminder, we repeat algo after in REPEAT_INTERVAL_MINUTE
			}
		}
		
	}
	
	
	
	
	  private static String getUserId(SecurityContext sc, UriInfo uriInfo) {

		    try {
		      return sc.getUserPrincipal().getName();
		    } catch (NullPointerException e) {
		      return getViewerId(uriInfo);
		    } catch (Exception e) {
		      return null;
		    }
		  }
	  
	  private static String getViewerId(UriInfo uriInfo) {
		    
		    URI uri = uriInfo.getRequestUri();
		    String requestString = uri.getQuery();
		    if (requestString == null) {
		      return null;
		    }
		    String[] queryParts = requestString.split("&");
		    
		    for (String queryPart : queryParts) {
		      if (queryPart.startsWith("opensocial_viewer_id")) {
		        return queryPart.substring(queryPart.indexOf("=") + 1, queryPart.length());
		      }
		    }
		    
		    return null;
		  }
	 
	  
	  public class MessageReminder implements Comparable<MessageReminder>{
		    private String Summary;
		    private String Description;
		    private Date fromDate;
		    private Date toDate;
		    private long RepeatIntervalMinute;

		    
		    public String getSummary() {
				return Summary;
			}
			public void setSummary(String summary) {
				Summary = summary;
			}
			public String getDescription() {
				return Description;
			}
			public void setDescription(String description) {
				Description = description;
			}
			public Date getFromDate() {
				return fromDate;
			}
			public void setFromDate(Date fromDate) {
				this.fromDate = fromDate;
			}
			public Date getToDate() {
				return toDate;
			}
			public void setToDate(Date toDate) {
				this.toDate = toDate;
			}
			public long getRepeatIntervalMinute() {
				return RepeatIntervalMinute;
			}
			public void setRepeatIntervalMinute(long repeatIntervalMinute) {
				RepeatIntervalMinute = repeatIntervalMinute;
			}
			
			
			@Override
		    public int compareTo(MessageReminder commentMessage) {
		      return this.fromDate.compareTo(commentMessage.getFromDate());
		    }
		    
	  }
	

}
