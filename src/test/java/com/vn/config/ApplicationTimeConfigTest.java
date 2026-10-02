package com.vn.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationTimeConfigTest {

    @Test
    void applicationClockShouldUseConfiguredBusinessZone() {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        Clock clock = new ApplicationTimeConfig(new ApplicationTimeProperties(zone)).applicationClock();

        assertThat(clock.getZone()).isEqualTo(zone);
    }

    @Test
    void propertiesShouldFailFastWhenBusinessZoneIsMissing() {
        assertThatThrownBy(() -> new ApplicationTimeProperties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.time.zone is required");
    }
}
