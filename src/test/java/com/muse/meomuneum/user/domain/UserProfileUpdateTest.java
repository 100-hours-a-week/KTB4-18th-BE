package com.muse.meomuneum.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class UserProfileUpdateTest {

    @Test
    void keepsExistingProfileValuesWhenPatchFieldsAreOmitted() throws ReflectiveOperationException {
        User user = new User();
        set(user, "nickname", "existing-name");
        set(user, "birthYear", (short) 1994);
        set(user, "gender", UserGender.FEMALE);
        set(user, "profileImageUrl", "https://images.example.com/users/1/profile/current.png");
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 26, 0, 0);

        user.updateProfile(null, null, false, null, false, null, updatedAt);

        assertThat(user.getNickname()).isEqualTo("existing-name");
        assertThat(user.getBirthYear()).isEqualTo((short) 1994);
        assertThat(user.getGender()).isEqualTo(UserGender.FEMALE);
        assertThat(user.getProfileImageUrl()).isEqualTo("https://images.example.com/users/1/profile/current.png");
        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void clearsGenderWhenExplicitlyProvidedAsNull() throws ReflectiveOperationException {
        User user = new User();
        set(user, "gender", UserGender.MALE);

        user.updateProfile(null, null, false, null, true, null, LocalDateTime.of(2026, 10, 4, 0, 0));

        assertThat(user.getGender()).isNull();
    }

    @Test
    void updatesGenderWhenAnEnumValueIsExplicitlyProvided() throws ReflectiveOperationException {
        User user = new User();
        set(user, "gender", UserGender.MALE);

        user.updateProfile(null, null, false, UserGender.FEMALE, true, null,
                LocalDateTime.of(2026, 10, 4, 0, 0));

        assertThat(user.getGender()).isEqualTo(UserGender.FEMALE);
    }

    private static void set(User user, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = User.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(user, value);
    }
}
