package com.muse.apps.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.runtime.enabled", havingValue = "true")
@ComponentScan("com.muse.meomuneum.chat")
public class ChatRuntimeConfiguration {
}
