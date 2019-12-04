/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.kodicec.internal;

import static org.openhab.binding.kodicec.internal.KodiCecBindingConstants.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The {@link KodiCecHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Csaba Nagy - Initial contribution
 */
@NonNullByDefault
public class KodiCecHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(KodiCecHandler.class);

    private static final String PROPERTY_VERSION = "version";

    private final KodiCecSocket socket = new KodiCecSocket();
    private final WebSocketClient client = new WebSocketClient();

    @Nullable
    private ScheduledFuture<?> connectionCheckScheduler;

    // @Nullable
    // private KodiCecConfiguration config;

    public KodiCecHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (CHANNEL_CEC_COMMAND.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                handleCecCommand(command.toString());
                updateState(CHANNEL_CEC_COMMAND, UnDefType.UNDEF);
            } else if (RefreshType.REFRESH == command) {
                updateState(CHANNEL_CEC_COMMAND, UnDefType.UNDEF);
            }
        }
    }

    private int getIntConfigParameter(String key, int defaultValue) {
        Object obj = getConfig().get(key);
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        } else if (obj instanceof String) {
            return Integer.parseInt(obj.toString());
        }
        return defaultValue;
    }

    @Override
    public void initialize() {
        // logger.debug("Start initializing!");
        // config = getConfigAs(KodiCecConfiguration.class);

        // TODO: Initialize the handler.
        // The framework requires you to return from this method quickly. Also, before leaving this method a thing
        // status from one of ONLINE, OFFLINE or UNKNOWN must be set. This might already be the real thing status in
        // case you can decide it directly.
        // In case you can not decide the thing status directly (e.g. for long running connection handshake using WAN
        // access or similar) you should set status UNKNOWN here and then decide the real status asynchronously in the
        // background.

        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        // the framework is then able to reuse the resources from the thing handler initialization.
        // we set this upfront to reliably check status updates in unit tests.
        updateStatus(ThingStatus.UNKNOWN);

        // Example for background initialization:
        scheduler.execute(() -> {
            connectClient();
            setUpScheduler();
        });

        // logger.debug("Finished initializing!");

        // Note: When initialization can NOT be done set the status with more details for further
        // analysis. See also class ThingStatusDetail for all available status details.
        // Add a description to give user information to understand why thing does not work as expected. E.g.
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        // "Can not access device as username and/or password are invalid");
    }

    private synchronized void connectClient() {
        String host = getConfig().get(HOST_PARAMETER).toString();

        if (host == null || host.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No network address specified");
        } else {
            int port = getIntConfigParameter(WS_PORT_PARAMETER, 9090);
            try {
                URI wsUri = new URI("ws", null, host, port, "/jsonrpc", null, null);
                logger.debug("connectClient: try to connect to Kodi {}", wsUri);

                if (!client.isStarted()) {
                    client.start();
                }
                client.connect(socket, wsUri);
                updateStatus(ThingStatus.ONLINE);
            } catch (URISyntaxException e) {
                logger.error("Invalid URI", e);
                updateStatus(ThingStatus.OFFLINE);
            } catch (IOException e) {
                logger.error("IO exception", e);
                updateStatus(ThingStatus.OFFLINE);
            } catch (Exception e) {
                logger.error("Exception while starting the client", e);
                updateStatus(ThingStatus.OFFLINE);
            }
        }
    }

    private synchronized void setUpScheduler() {
        connectionCheckScheduler = scheduler.scheduleWithFixedDelay(() -> {
            checkConnection();
        }, 1, getIntConfigParameter(REFRESH_PARAMETER, 10), TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        try {
            if (connectionCheckScheduler != null) {
                connectionCheckScheduler.cancel(true);
            }
            client.stop();
        } catch (Exception e) {
            logger.error("Could not stop client");
        }
        super.dispose();
    }

    public void checkConnection() {
        if (!socket.isConnected()) {
            logger.debug("checkConnection: try to connect to Kodi");
            connectClient();
        } else {
            getVersion();
        }
    }

    public String getVersion() {
        if (socket.isConnected()) {
            String[] props = { PROPERTY_VERSION };

            JsonObject params = new JsonObject();
            params.add("properties", getJsonArray(props));
            JsonElement response = socket.callMethod("Application.GetProperties", params);

            if (response instanceof JsonObject) {
                JsonObject result = response.getAsJsonObject();
                if (result.has(PROPERTY_VERSION)) {
                    JsonObject version = result.get(PROPERTY_VERSION).getAsJsonObject();
                    int major = version.get("major").getAsInt();
                    int minor = version.get("minor").getAsInt();
                    String revision = version.get("revision").getAsString();
                    return String.format("%d.%d (%s)", major, minor, revision);
                }
            }
        }
        return "";
    }

    private void handleCecCommand(String command) {
        JsonObject params = new JsonObject();
        JsonObject params2 = new JsonObject();
        params2.addProperty("command", command);
        params.addProperty("addonid", "script.json-cec");
        params.add("params", params2);
        socket.callMethod("Addons.ExecuteAddon", params);
    }

    private JsonArray getJsonArray(String[] values) {
        JsonArray result = new JsonArray();
        for (String param : values) {
            result.add(new JsonPrimitive(param));
        }
        return result;
    }

}
