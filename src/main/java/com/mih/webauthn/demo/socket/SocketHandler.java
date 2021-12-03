package com.mih.webauthn.demo.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mih.webauthn.demo.RandomCodeService;
import io.github.webauthn.domain.WebAuthnUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SocketHandler.class);
    private static final SecureRandom random = new SecureRandom();

    private final ObjectMapper mapper;
    private final RandomCodeService codeService;

    Map<String, List<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public SocketHandler(ObjectMapper mapper, RandomCodeService codeService) {
        this.mapper = mapper;
        this.codeService = codeService;
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws InterruptedException, IOException {
        Principal principal = session.getPrincipal();
        log.debug("handleTextMessage - principal: {}, payload: {}", principal, message.getPayload());

        String myCode = (String) session.getAttributes().get("my-code");
        if (myCode != null) {
            sendMessage(session, message, rooms.get(myCode));
        }

        Optional.ofNullable((String) session.getAttributes().get("code"))
                .map(existingSessionCode -> {
                    log.debug("handleTextMessage - existingSessionCode: {}, payload: {}", existingSessionCode, message.getPayload());
                    List<WebSocketSession> list = rooms.get(existingSessionCode);
                    sendMessage(session, message, list);
                    return existingSessionCode;
                })
                .orElseGet(() -> {
                    try {
                        RoomRequest room = mapper.readValue(message.getPayload(), RoomRequest.class);
                        session.getAttributes().put("code", room.getCode());
                        if (rooms.get(room.getCode()).size() < 2) {
                            rooms.get(room.getCode()).add(session);
                        }
                        UsernamePasswordAuthenticationToken t = (UsernamePasswordAuthenticationToken) session.getPrincipal();
                        String payload = "{\"user\": \"" + ((WebAuthnUser) t.getPrincipal()).getUsername() + "\"}";
                        log.debug("handleTextMessage - new code: {}, payload: {}, new payload: ", room.getCode(), message.getPayload(), payload);
                        sendMessage(session, new TextMessage(payload), rooms.get(room.getCode()));

                        return room.getCode();
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                });

    }

    private void sendMessage(WebSocketSession session, TextMessage message, List<WebSocketSession> list) {

        List<WebSocketSession> unavailable = new ArrayList<>();
        list
                .forEach(d -> {
                    try {
                        if (d.isOpen() && !session.getId().equals(d.getId())) {
                            d.sendMessage(message);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (!d.isOpen()) {
                            unavailable.add(session);
                        }
                    }
                });
        if (!unavailable.isEmpty()) {
            log.info("sendMessage - removing {} unavailable sessions", unavailable.size());
            list.removeAll(unavailable);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Principal principal = session.getPrincipal();
        if (principal == null) {
            String code = codeService.nextString();
            session.sendMessage(new TextMessage("{\"code\": \"" + code + "\"}"));
            List<WebSocketSession> list = new ArrayList<>();
            list.add(session);
            session.getAttributes().put("my-code", code);
            rooms.put(code, list);
        }
    }
}
