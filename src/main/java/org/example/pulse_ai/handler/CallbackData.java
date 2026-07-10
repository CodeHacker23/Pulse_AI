package org.example.pulse_ai.handler;

public final class CallbackData {

    public static final String PREFIX_MENU = "menu:";
    public static final String PREFIX_CHANNEL = "channel:";
    public static final String PREFIX_REQ = "req:";
    public static final String PREFIX_RESULT = "result:";
    public static final String PREFIX_IDEA = "idea:";
    public static final String PREFIX_POST = "post:";
    public static final String PREFIX_PUBLISH = "publish:";
    public static final String PREFIX_PAY = "pay:";
    public static final String PREFIX_HIST = "hist:";
    public static final String PREFIX_SET = "set:";

    public static final String MENU_MAIN = PREFIX_MENU + "main";
    public static final String MENU_HOW_IT_WORKS = PREFIX_MENU + "howItWorks";
    public static final String MENU_WHAT_IN_REQUEST = PREFIX_MENU + "whatInRequest";

    public static final String CHANNEL_CONNECT = PREFIX_CHANNEL + "connect";
    public static final String CHANNEL_CONNECT_LIMITED = PREFIX_CHANNEL + "connect:limited";

    public static final String REQ_FREE = PREFIX_REQ + "free";
    public static final String REQ_START = PREFIX_REQ + "start";
    public static final String REQ_CONFIRM = PREFIX_REQ + "confirm";

    public static final String PAY_SELECT = PREFIX_PAY + "select";

    private CallbackData() {
    }
}
