package org.openqcm.core;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.ardulink.util.Preconditions.checkNotNull;
import static org.ardulink.util.Preconditions.checkState;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.math3.stat.descriptive.rank.Median;
import org.ardulink.core.Link;
import org.ardulink.core.events.CustomEvent;
import org.ardulink.core.events.CustomListener;
import org.ardulink.core.events.RplyEvent;
import org.ardulink.core.qos.ResponseAwaiter;
import org.openqcm.core.event.OpenQCMEvent;
import org.openqcm.core.event.OpenQCMListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArdulinkConnector implements CustomListener {

	private List<OpenQCMListener> listeners = new CopyOnWriteArrayList<>();
	
	private static final Logger logger = LoggerFactory.getLogger(ArdulinkConnector.class);

	private static final String GET_UNIQUE_ID_CUSTOM_MESSAGE = "getUniqueID";
	private static final String UNIQUE_ID_PARAMETER_VALUE_KEY = "UniqueID";
	
	private Link link;
	private String deviceID;

    // size of circular buffer
    private final int bufferSize = 10;

    // frequency circular buffer for eliminating signal glitches using median
    private ArrayCircularBuffer<Double> bufferFrequency = new ArrayCircularBuffer<Double>(bufferSize/2);
    // frequency circular buffer for averaging frequency data
    private ArrayCircularBuffer<Integer> bufferFrequencyTemp = new ArrayCircularBuffer<Integer>(bufferSize);
    // temperauture circular buffer for averaging temperature data
    private ArrayCircularBuffer<Integer> bufferTemperature = new ArrayCircularBuffer<Integer>(bufferSize);
    
    // temperature circular buffer for smoothing data
    // ArrayCircularBuffer bufferTemperatureTemp = new ArrayCircularBuffer(bufferSize/2);
    // nominal quartz crystal frequency
    private int nominalFrequency = 6000000;

    // Arduino half timer clock
    private static final int ALIAS = 8000000;

	public void setNominalFrequency(int nominalFrequency) {
		this.nominalFrequency = nominalFrequency;
	}

	public boolean addOpenQCMListener(OpenQCMListener e) {
		return listeners.add(e);
	}

	public boolean removeOpenQCMListener(Object o) {
		return listeners.remove(o);
	}

	private void fireOpenQCMValueReceived(OpenQCMEvent event) {
		for (OpenQCMListener listener : this.listeners) {
			try {
				listener.incomingEvent(event);
			} catch (Exception e) {
				logger.error("OpenQCMListener {} failure",listener, e);
			}
		}
		
	}

	@Override
	public void customEventReceived(CustomEvent customEvent) {
        String messageString = customEvent.getMessage();

        // if the message starts with the string "RAWMONITOR" display and store data
        if (messageString.startsWith("RAWMONITOR")) {
            // print the value on the screen
        	logger.debug(messageString);
            messageString = messageString.substring("RAWMONITOR".length());
            OpenQCMIncomingValue value = computeValue(messageString);
            fireOpenQCMValueReceived(new OpenQCMEvent(value, getLinkID()));
        }		
	}

	public String getLinkID() {
		return deviceID;
	}

	private OpenQCMIncomingValue computeValue(String messageString) {
        String[] dataSplits = messageString.split("_");
        int dataFrequency = Integer.parseInt(dataSplits[0]);
        int dataTemperature = Integer.parseInt(dataSplits[1]);

        /* 
         * Frequency Median implemented using Apache commons Math
         * frequency data are affected by some glitches due to the 
         * algorithm for counting pulses during a fixed time interval
         * median is a robust algorithm for smoothing frequency data
         * and for eliminating outliers
         * Frequency data processing algorithm: averaging and calculate median 
         */
        
        // insert new frequency data in circuar buffer and calculate the average
        bufferFrequencyTemp.insert(dataFrequency);
        double sum = 0;
        for (int i = 0; i < bufferFrequencyTemp.size(); i++) {
            sum = sum + bufferFrequencyTemp.getData(i);
        }
        // Average frequency data 
        double averageFrequency = sum / bufferFrequencyTemp.size();
        // insert new average frequency data in circuar buffer and calculate median
        bufferFrequency.insert(averageFrequency);
        // read the circular buffer
        int count = bufferFrequency.size();
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
        	values[i] = bufferFrequency.getData(i);
        } 
        Median median = new Median();
        // calculate the median of frequency data
        double meanFrequency = median.evaluate(values);
        // alias arduino timer 
        if (nominalFrequency == 10000000) {
        	meanFrequency = (2 * ALIAS) - meanFrequency;
        } 
        
        // insert temperature data in circuar buffer and calculate the average 
        bufferTemperature.insert(dataTemperature);
        double sumT = 0;
        for (int i = 0; i < bufferTemperature.size(); i++) {
            sumT = sumT + bufferTemperature.getData(i);
        }
        // Average temperature data
        double meanTemperature = sumT / bufferTemperature.size();
        // TODO divide by 10 for decimal
        meanTemperature = meanTemperature/10;
        
        return new OpenQCMIncomingValue(meanTemperature, meanFrequency);
        
	}

	/**
	 * Sets the Link for this connector. Connector begins to listen for custom events from Ardulink.
	 * If Link is null then the connector disconnects itself from listening custom events.
	 * @param link
	 * @throws IOException
	 */
	public void setLink(Link link) throws IOException {
		
		if(link == null) {

			if(this.link != null) {
				this.link.removeCustomListener(this);
			}
            this.link = null;
            this.deviceID = null;
			
		} else {
        	setLink(null);
            this.link = link;
            this.link.addCustomListener(this);
            deviceID = initID();
			
		}
	}

	private String initID() throws IOException {
		String uniqueID;
		RplyEvent rplyEvent = ResponseAwaiter.onLink(link)
				.withTimeout(5, SECONDS)
				.waitForResponse(sendUniqueIdCustomMsg(link));
		
		checkState(rplyEvent.isOk(), "Something went wrong on reply from Device searching for unique ID.");
		uniqueID = checkNotNull(rplyEvent.getParameterValue(UNIQUE_ID_PARAMETER_VALUE_KEY), "Reply doesn't contain UniqueID").toString();

		return uniqueID;
	}

	private long sendUniqueIdCustomMsg(Link link) throws IOException {
		return link.sendCustomMessage(GET_UNIQUE_ID_CUSTOM_MESSAGE,	getSuggestedUniqueID());
	}
	
	private String getSuggestedUniqueID() {
		String sugestedUniqueID = UUID.randomUUID().toString();
		return sugestedUniqueID;
	}
}
