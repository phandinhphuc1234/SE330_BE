package com.vn.notification.service;

import com.vn.notification.service.EmailNotificationCommand;
import com.vn.notification.service.NotificationEnqueueResult;

public interface NotificationQueueService {

    NotificationEnqueueResult enqueueEmail(EmailNotificationCommand command);
}
