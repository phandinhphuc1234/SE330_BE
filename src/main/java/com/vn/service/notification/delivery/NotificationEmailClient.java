package com.vn.service.notification.delivery;

public interface NotificationEmailClient {

    EmailProviderResult send(RenderedNotificationEmail email);
}
