package com.amz.client;

import java.util.Objects;

/**
 * Amazon Messaging API 支持的消息动作。
 *
 * <p>枚举值直接对应官方 messaging.json 的 operationId 与发送路径段；
 * 这里刻意不提供“通用 reply”语义，因为官方 Messaging API 不支持任意回复。
 */
public enum MessagingAction {
    CONFIRM_CUSTOMIZATION_DETAILS("messaging.confirmCustomizationDetails", "confirmCustomizationDetails"),
    CONFIRM_DELIVERY_DETAILS("messaging.createConfirmDeliveryDetails", "confirmDeliveryDetails"),
    LEGAL_DISCLOSURE("messaging.createLegalDisclosure", "legalDisclosure"),
    CONFIRM_ORDER_DETAILS("messaging.createConfirmOrderDetails", "confirmOrderDetails"),
    CONFIRM_SERVICE_DETAILS("messaging.createConfirmServiceDetails", "confirmServiceDetails"),
    WARRANTY("messaging.CreateWarranty", "warranty"),
    DIGITAL_ACCESS_KEY("messaging.createDigitalAccessKey", "digitalAccessKey"),
    UNEXPECTED_PROBLEM("messaging.createUnexpectedProblem", "unexpectedProblem"),
    INVOICE("messaging.sendInvoice", "invoice");

    private final String operationId;
    private final String pathSegment;

    MessagingAction(String operationId, String pathSegment) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.pathSegment = Objects.requireNonNull(pathSegment, "pathSegment");
    }

    public String operationId() {
        return operationId;
    }

    public String pathSegment() {
        return pathSegment;
    }
}
