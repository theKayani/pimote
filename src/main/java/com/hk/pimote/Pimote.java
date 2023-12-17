package com.hk.pimote;

import com.hk.io.mqtt.Client;
import com.hk.io.mqtt.Message;
import com.hk.io.mqtt.MessageConsumer;
import com.hk.json.Json;
import com.hk.json.JsonObject;
import com.hk.json.JsonValue;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalState;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Pimote implements MessageConsumer
{
	static
	{
		System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %4$s]: %5$s%6$s%n");
	}

	static final Logger LOG = Logger.getLogger("Pimote");
	final Client client;
	final AtomicBoolean stopped = new AtomicBoolean(false);
	final Object waitLock = new Object();
	final Context pi4j;
	final DigitalOutput swtch;

	private Pimote()
	{
		String[] sp = MQTT_CTRL.split(":");
		String host = sp[0];
		int port = Integer.parseInt(sp[1]);
		LOG.info("using host: " + host + ", and port: " + port);
		client = new Client(host, port);

		client.setUsername(MQTT_USER);
		client.setRawPassword(MQTT_PASS.getBytes(StandardCharsets.UTF_8));
		client.getLogger().setParent(LOG);
		client.setLogLevel(Level.FINER);
		client.setDefaultExceptionHandler();
		client.setMessageConsumer(this);
		client.setLastWill(new Message(MQTT_PREFIX + "/list/" + DEVICE_TAG, getProps(false), 1, true));

		if(!GPIO_DISABLED)
		{
			pi4j = Pi4J.newAutoContext();

			LOG.info("Pi4J PLATFORMS");
			LOG.info(pi4j.platforms().describe().description());

			LOG.info("Loaded GPIO Controller");

			DigitalOutputConfigBuilder ledConfig = DigitalOutput.newConfigBuilder(pi4j)
					.id("switch")
					.name("Switch")
					.address(GPIO_PIN)
					.shutdown(DigitalState.LOW)
					.initial(DigitalState.LOW)
					.provider("pigpio-digital-output");

			swtch = pi4j.create(ledConfig);

			LOG.info("Connected to pin " + GPIO_PIN + ", " + swtch.getName());
		}
		else
		{
			pi4j = null;
			swtch = null;
			LOG.info("IGNORING GPIO CONTROLLER");
		}
	}

	private void start() throws InterruptedException
	{
		synchronized (waitLock)
		{
			if(!GPIO_DISABLED)
			{
				for (int i = 1; i <= 6; i++)
				{
					boolean state = i % 2 == 0;
					LOG.info("Blinking " + (state ? "on" : "off"));
					swtch.setState(state);
					wt(500);
				}
			}
			client.connect();
			wt(1000);

			if(!client.isAuthorized())
				throw new IllegalStateException("client hasn't authorized with broker!");

			client.publish(MQTT_PREFIX + "/list/" + DEVICE_TAG, getProps(true), 1, true);
			client.subscribe(MQTT_PREFIX + "/set/" + DEVICE_TAG, 1);
			client.subscribe(MQTT_PREFIX + "/restart/" + DEVICE_TAG, 1);
			do
			{
				waitLock.wait(5000);
			} while (client.isAuthorized() && !stopped.get());
		}
	}

	private String getProps(boolean online)
	{
		JsonObject properties = new JsonObject();
		properties.put("name", DEVICE_NAME);
		properties.put("type", "switch");
		properties.put("state", (online ? "on" : "off") + "line");
		return Json.write(properties);
	}

	@Override
	public void consume(Message message)
	{
		JsonObject json;
		String topic = message.getTopic();
		try
		{
			JsonValue val = message.toInput().toJson();
			LOG.fine("GOT MESSAGE: " + Json.write(val));

			if(!val.isObject())
				throw new RuntimeException("JSON value must be an object");

			json = val.getObject();
		}
		catch (Exception ex)
		{
			LOG.log(Level.WARNING, "INVALID JSON MESSAGE RECEIVED ON TAG: " + message.getTopic(), ex);
			return;
		}

		if(topic.equals(MQTT_PREFIX + "/set/" + DEVICE_TAG))
		{
			boolean state = json.getBoolean("state");

			LOG.info("Changing switch state: " + state);
			if(GPIO_DISABLED)
				LOG.warning("GPIO DISABLED!");
			else
				swtch.setState(state);
		}
		else if(topic.equals(MQTT_PREFIX + "/restart/" + DEVICE_TAG))
		{
			String key = json.getString("key");
			if(key.equals(DEVICE_KEY))
			{
				LOG.warning("server stop received with correct key");
				stop();
			}
			else
				LOG.warning("INCORRECT DEVICE KEY");
		}
	}

	private void stop()
	{
		if(stopped.get())
			return;

		stopped.set(true);
		LOG.warning("stopping server and closing resources");

		if(client.isAuthorized())
			client.publish(Objects.requireNonNull(client.getLastWill()));
		if(!GPIO_DISABLED)
			pi4j.shutdown();

		client.disconnect(!client.isAuthorized());
		wt(1000);
		synchronized (waitLock)
		{
			waitLock.notifyAll();
		}
	}

	@SuppressWarnings("InfiniteLoopStatement")
	public static void main(String[] args)
	{
		if(MQTT_USER == null || MQTT_USER.trim().isEmpty())
		{
			LOG.severe("missing mqtt parameter: 'mqtt.user'");
			return;
		}
		if(MQTT_PASS == null || MQTT_PASS.trim().isEmpty())
		{
			LOG.severe("missing mqtt parameter: 'mqtt.pass'");
			return;
		}
		if(MQTT_CTRL == null || MQTT_CTRL.trim().isEmpty())
		{
			LOG.severe("missing mqtt parameter: 'mqtt.ctrl'");
			return;
		}
		if(MQTT_PREFIX == null || MQTT_PREFIX.trim().isEmpty())
		{
			LOG.severe("missing mqtt parameter: 'mqtt.prefix'");
			return;
		}
		if(DEVICE_NAME == null || DEVICE_NAME.trim().isEmpty())
		{
			LOG.severe("missing pimote parameter: 'pimote.name'");
			return;
		}
		if(DEVICE_TAG == null || DEVICE_TAG.trim().isEmpty())
		{
			LOG.severe("missing pimote parameter: 'pimote.tag'");
			return;
		}
		if(DEVICE_KEY == null || DEVICE_KEY.trim().isEmpty())
		{
			LOG.severe("missing pimote parameter: 'pimote.key'");
			return;
		}
		if(!GPIO_DISABLED && GPIO_PIN == null)
		{
			LOG.severe("missing integer gpio parameter: 'gpio.pin'");
			return;
		}

		AtomicReference<Pimote> pimote = new AtomicReference<>(null);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> pimote.get().stop()));
		boolean initialized;
		while(true)
		{
			initialized = false;
			try
			{
				pimote.set(new Pimote());
				initialized = true;
				pimote.get().start();
			}
			catch (Exception e)
			{
				LOG.log(Level.SEVERE, "error while " + (initialized ? "starting" : "creating"), e);
			}
			finally
			{
				if(pimote.get() != null)
					pimote.get().stop();
				pimote.set(null);
			}
			wt(10000);
		}
	}

	public static void wt(final long ms)
	{
		try
		{
			Thread.sleep(ms);
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
	}

	private static final String MQTT_USER = System.getProperty("mqtt.user");
	private static final String MQTT_PASS = System.getProperty("mqtt.pass");
	private static final String MQTT_CTRL = System.getProperty("mqtt.ctrl");
	private static final String MQTT_PREFIX = System.getProperty("mqtt.prefix", "pimotev3");
	private static final String DEVICE_NAME = System.getProperty("pimote.name");
	private static final String DEVICE_TAG = System.getProperty("pimote.tag");
	private static final String DEVICE_KEY = System.getProperty("pimote.key");
	private static final Integer GPIO_PIN = Integer.getInteger("gpio.pin");
	private static final boolean GPIO_DISABLED = Boolean.getBoolean("gpio.disabled");
}