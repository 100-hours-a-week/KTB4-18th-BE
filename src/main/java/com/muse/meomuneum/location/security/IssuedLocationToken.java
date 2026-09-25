package com.muse.meomuneum.location.security;

public record IssuedLocationToken(String value, long expiresIn) {
}
