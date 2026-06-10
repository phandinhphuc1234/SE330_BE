package com.vn.service.storage;

public enum MediaResourceType {
    IMAGE("image"),
    VIDEO("video"),
    RAW("raw"),
    AUTO("auto");

    private final String cloudinaryValue;

    MediaResourceType(String cloudinaryValue) {
        this.cloudinaryValue = cloudinaryValue;
    }

    public String cloudinaryValue() {
        return cloudinaryValue;
    }
}
