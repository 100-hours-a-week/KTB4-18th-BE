package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class ChatMusicCursorTest {
    @Test
    void rotationAcceptsRetainedKeyAndRejectsTamperingScopeChangeExpiryAndUnknownKey() {
        String first = "old:" + Base64.getEncoder().encodeToString(new byte[32]);
        String second = "new:" + Base64.getEncoder().encodeToString(new byte[48]);
        var old = new ChatMusicCursor(new ChatMusicKeys(first));
        String cursor = old.encode("account-room-view-query-size", 25, 7, 1000);
        var rotated = new ChatMusicCursor(new ChatMusicKeys(second + "," + first));
        assertThat(rotated.decode(cursor, "account-room-view-query-size", 1001))
                .isEqualTo(new ChatMusicCursor.Page(25, 7));
        assertThatThrownBy(() -> rotated.decode(cursor + "a", "account-room-view-query-size", 1001))
                .isInstanceOf(ChatMusicException.class);
        assertThatThrownBy(() -> rotated.decode(cursor, "other-account", 1001)).isInstanceOf(ChatMusicException.class);
        assertThatThrownBy(() -> rotated.decode(cursor, "account-room-view-query-size", 1300))
                .isInstanceOfSatisfying(ChatMusicException.class, error -> assertThat(error.status()).isEqualTo(410));
        assertThatThrownBy(() -> new ChatMusicCursor(new ChatMusicKeys(second))
                .decode(cursor, "account-room-view-query-size", 1001)).isInstanceOf(ChatMusicException.class);
    }

    @Test
    void emptyWeakOrDuplicateKeyRingsAreRejected() {
        for (String input : new String[]{"", "key:YWJj", "x:" + Base64.getEncoder().encodeToString(new byte[32])
                + ",x:" + Base64.getEncoder().encodeToString(new byte[32])}) {
            assertThatThrownBy(() -> new ChatMusicKeys(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
