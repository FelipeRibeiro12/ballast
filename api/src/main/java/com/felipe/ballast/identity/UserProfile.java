package com.felipe.ballast.identity;

import java.util.UUID;

public record UserProfile(UUID id, String email, String name) {}
