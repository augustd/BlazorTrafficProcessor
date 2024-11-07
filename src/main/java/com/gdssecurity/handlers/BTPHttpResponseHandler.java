/**
 * Copyright 2023 Aon plc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gdssecurity.handlers;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import com.gdssecurity.helpers.BTPConstants;
import com.gdssecurity.providers.BTPContextMenuItemsProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Class to handle the downgrade from WS to LongPolling (HTTP)
 */
public class BTPHttpResponseHandler implements ProxyResponseHandler {

    private MontoyaApi _montoya;
    private Logging _logging;
    private JSONArray modifiedTransports;

    /**
     * Constructor for the BTPHttpResponseHandler object
     * @param montoyaApi - an instance of the Burp Montoya APIs
     */
    public BTPHttpResponseHandler(MontoyaApi montoyaApi) {
        this._montoya = montoyaApi;
        this._logging = montoyaApi.logging();
        this.modifiedTransports = BTPConstants.DOWNGRADED_TRANSPORTS;
    }

    /**
     * Handles the downgrade by listening for matching HTTP responses and auto-modifying them to omit WS
     * @param interceptedResponse - An object containing the intercepted HTTP response
     * @return the downgraded body if applicable, otherwise just let the response go through un-touched
     */
    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse interceptedResponse) {
        // Highlight
        if (interceptedResponse.statedMimeType() == MimeType.APPLICATION_UNKNOWN && interceptedResponse.body().length() != 0) {
            interceptedResponse.annotations().setHighlightColor(HighlightColor.CYAN);
        }

        // Handle Blazor Negotiation
        if (!interceptedResponse.initiatingRequest().url().contains(BTPConstants.NEGOTIATE_URL) || interceptedResponse.statedMimeType() != MimeType.JSON) {
            return ProxyResponseReceivedAction.continueWith(interceptedResponse);
        }
        try {
            JSONObject body = new JSONObject(interceptedResponse.bodyToString());
            if (body.has("availableTransports")) {
                boolean wsEnabled = false;
                JSONArray transports = new JSONArray(body.getJSONArray("availableTransports"));
                for (int i = 0; i < transports.length(); i++) {
                    JSONObject transport = transports.getJSONObject(i);
                    if (transport.has("transport") && transport.getString("transport").equals("WebSockets")) {
                        wsEnabled = true;
                    }
                }
                if (!wsEnabled) {
                    // WS not enabled, no need to downgrade
                    return ProxyResponseReceivedAction.continueWith(interceptedResponse);
                } else {
                    body.remove("availableTransports");

                    modifiedTransports = new JSONArray();
                    boolean wsText = BTPContextMenuItemsProvider.getBoolean(_montoya, "WebSockets: Text", false);
                    boolean wsBinary = BTPContextMenuItemsProvider.getBoolean(_montoya, "WebSockets: Binary", false);
                    boolean ssText = BTPContextMenuItemsProvider.getBoolean(_montoya, "ServerSentEvents: Text", true);
                    boolean lpText = BTPContextMenuItemsProvider.getBoolean(_montoya, "LongPolling: Text", true);
                    boolean lpBinary = BTPContextMenuItemsProvider.getBoolean(_montoya, "LongPolling: Binary", true);

                    if (wsText||wsBinary) {
                        JSONObject ws = new JSONObject();
                        ws.append("transport", "WebSockets");
                        JSONArray formats = new JSONArray();
                        if (wsText) {
                            formats.put("Text");
                        }
                        if (wsBinary) {
                            formats.put("Binary");
                        }
                        ws.put("transferFormats", formats);
                        modifiedTransports.put(ws);
                    }
                    if (ssText) {
                        JSONObject ss = new JSONObject();
                        ss.append("transport", "ServerSentEvents");
                        JSONArray formats = new JSONArray();
                        formats.put("Text");
                        ss.put("TransferFormats", formats);
                        modifiedTransports.put(ss);
                    }
                    if (lpText||lpBinary) {
                        JSONObject lp = new JSONObject();
                        lp.append("transport", "LongPolling");
                        JSONArray formats = new JSONArray();
                        if (lpText) {
                            formats.put("Text");
                        }
                        if (lpBinary) {
                            formats.put("Binary");
                        }
                        lp.put("transferFormats", formats);
                        modifiedTransports.put(lp);
                    }
                    body.put("availableTransports", modifiedTransports);
                    return ProxyResponseReceivedAction.continueWith(interceptedResponse.withBody(body.toString()));
                }
            }
        } catch (JSONException jsonE) {
            this._logging.logToError("[-] handleResponseReceived - An error occurred while reading JSON body for downgrade: " + jsonE.getMessage());
            return ProxyResponseReceivedAction.continueWith(interceptedResponse);
        } catch (Exception e) {
            this._logging.logToError("[-] handleResponseReceived - An unexpected exception occurred when performing the downgrade: " + e.getMessage());
            return ProxyResponseReceivedAction.continueWith(interceptedResponse);
        }
        return ProxyResponseReceivedAction.continueWith(interceptedResponse);
    }

    /**
     * Handles the logic for after a response has been processed
     * Just forward along the un-touched response since it was already modified by handleResponseReceived
     * @param interceptedResponse - An object holding the HTTP response right before it is sent
     * @return the un-touched response object
     */
    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse interceptedResponse) {
        return ProxyResponseToBeSentAction.continueWith(interceptedResponse);
    }
}
