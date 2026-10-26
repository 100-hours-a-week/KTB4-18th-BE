package com.muse.meomuneum.chat.auth;

import java.util.List;

import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

@FunctionalInterface
public interface CoreSessionGateway {
    List<SessionDecision> validate(List<SessionPair> pairs);
}
