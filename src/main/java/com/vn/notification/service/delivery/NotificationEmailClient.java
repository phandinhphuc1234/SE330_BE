package com.vn.notification.service.delivery;

public interface NotificationEmailClient {

    EmailProviderResult send(RenderedNotificationEmail email);
}
