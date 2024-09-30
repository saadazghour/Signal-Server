/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.websocket;

import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.entities.ProvisioningMessage;
import org.whispersystems.textsecuregcm.push.ProvisioningManager;
import org.whispersystems.textsecuregcm.storage.PubSubProtos;
import org.whispersystems.textsecuregcm.util.HeaderUtils;
import org.whispersystems.websocket.session.WebSocketSessionContext;
import org.whispersystems.websocket.setup.WebSocketConnectListener;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A "provisioning WebSocket" provides a mechanism for sending a caller-defined provisioning message from the primary
 * device associated with a Signal account to a new device that is not yet associated with a Signal account. Generally,
 * the message contains key material and credentials the new device needs to associate itself with the primary device's
 * Signal account.
 * <p>
 * New devices initiate the provisioning process by opening a provisioning WebSocket. The server assigns the new device
 * a random, temporary "provisioning address," which it transmits via the newly-opened WebSocket. From there, the new
 * device generally displays the provisioning address (and a public key) as a QR code. After that, the primary device
 * will scan the QR code and send an encrypted provisioning message to the new device via
 * {@link org.whispersystems.textsecuregcm.controllers.ProvisioningController#sendProvisioningMessage(AuthenticatedDevice, String, ProvisioningMessage, String)}.
 * Once the server receives the message from the primary device, it sends the message to the new device via the open
 * WebSocket, then closes the WebSocket connection.
 */
public class ProvisioningConnectListener implements WebSocketConnectListener {

  private final ProvisioningManager provisioningManager;

  public ProvisioningConnectListener(final ProvisioningManager provisioningManager) {
    this.provisioningManager = provisioningManager;
  }

  @Override
  public void onWebSocketConnect(WebSocketSessionContext context) {
    final String provisioningAddress = UUID.randomUUID().toString();
    context.addWebsocketClosedListener((context1, statusCode, reason) -> provisioningManager.removeListener(provisioningAddress));

    provisioningManager.addListener(provisioningAddress, message -> {
      assert message.getType() == PubSubProtos.PubSubMessage.Type.DELIVER;

      final Optional<byte[]> body = Optional.of(message.getContent().toByteArray());

      context.getClient().sendRequest("PUT", "/v1/message", List.of(HeaderUtils.getTimestampHeader()), body)
          .whenComplete((ignored, throwable) -> context.getClient().close(1000, "Closed"));
    });

    context.getClient().sendRequest("PUT", "/v1/address", List.of(HeaderUtils.getTimestampHeader()),
        Optional.of(generateProvisioningAddress().toByteArray()));
  }

  private static MessageProtos.ProvisioningAddress generateProvisioningAddress() {
    final byte[] provisioningAddress = new byte[16];
    new SecureRandom().nextBytes(provisioningAddress);

    return MessageProtos.ProvisioningAddress.newBuilder()
        .setAddress(Base64.getUrlEncoder().encodeToString(provisioningAddress))
        .build();
  }
}
