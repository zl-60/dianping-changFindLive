package com.petcare;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pet.expiry-enabled",havingValue="true",matchIfMissing=true)
public class PetExpiry {
    private final PetBookingService booking;
    public PetExpiry(PetBookingService booking) { this.booking=booking; }
    @Scheduled(fixedDelay=15000,initialDelay=30000)
    public void releaseExpired() { booking.expire(); }
}
