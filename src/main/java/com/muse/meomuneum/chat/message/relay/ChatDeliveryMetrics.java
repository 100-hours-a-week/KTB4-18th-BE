package com.muse.meomuneum.chat.message.relay;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Fixed stages only: no account, room, socket, SQL, message body or exception dimensions. */
public final class ChatDeliveryMetrics {
    // @formatter:off
    public enum Stage {
        RECEIVER_QUEUE,
        DELIVERY_LOCK_WAIT,
        DELIVERY_LOCK_HOLD,
        MESSAGE_LOOKUP_DB,
        RECIPIENT_LOOKUP_DB,
        LOCAL_RECIPIENT_SNAPSHOT,
        RECIPIENT_AUTH,
        BROKER_ENQUEUE,
        FANOUT,
        MESSAGE_PERMISSION_BATCH_DB,
        MESSAGE_PERMISSION_BATCH_WAIT,
        MESSAGE_PERMISSION_DB,
        PRESENCE_PERMISSION_DB,
        PRESENCE_PERMISSION_BATCH_DB,
        PRESENCE_PERMISSION_BATCH_WAIT,
        PRESENCE_RECIPIENT_LOOKUP_DB,
        MESSAGE_ENQUEUE_GUARD,
        PRESENCE_ENQUEUE_GUARD,
        MESSAGE_HANDLE_GUARD,
        PRESENCE_HANDLE_GUARD,
        OUTBOUND_HANDLER,
        LOCAL_SUBSCRIPTION_WAIT,
        LOCAL_SUBSCRIPTION_HOLD,
        PRESENCE_TRANSITION_WAIT,
        PRESENCE_TRANSITION_HOLD,
        TRANSITION_ADMISSION,
        TRANSITION_TRANSACTION,
        ROOM_LOCK_AND_BOUNDARIES,
        SOCKET_VALIDATION_DB,
        OUTBOX_CLAIM,
        OUTBOX_PUBLISHABLE,
        OUTBOX_COMPLETE,
        ROOM_UPSERT_DB,
        ROOM_FOR_UPDATE_DB,
        BOUNDARY_INIT_DB
    }
    // @formatter:on

    private final EnumMap<Stage, Timer> timers = new EnumMap<>(Stage.class);
    private final Counter eligible;
    private final Counter recipients;

    public ChatDeliveryMetrics(MeterRegistry registry) {
        for (Stage stage : Stage.values()) {
            timers.put(stage, Timer.builder("chat.delivery.stage.duration")
                    .tag("stage", stage.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        eligible = Counter.builder("chat.delivery.eligible").register(registry);
        recipients = Counter.builder("chat.delivery.recipients").register(registry);
    }

    public static void record(ChatDeliveryMetrics metrics, Stage stage, long started) {
        if (metrics != null) {
            try {
                metrics.timers.get(stage).record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                // Observability must never change delivery or permission decisions.
            }
        }
    }

    public void recipients(int eligibleCount, int recipientCount) {
        try {
            eligible.increment(eligibleCount);
            recipients.increment(recipientCount);
        } catch (RuntimeException ignored) {
            // Keep recipient selection independent of the registry.
        }
    }
}
