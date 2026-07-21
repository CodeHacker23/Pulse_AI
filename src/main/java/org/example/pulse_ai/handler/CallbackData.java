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
    public static final String PREFIX_PERK = "perk:";
    public static final String PREFIX_FEAT = "feat:";
    public static final String PREFIX_HIST = "hist:";
    public static final String PREFIX_SET = "set:";
    public static final String PREFIX_PRODUCT = "product:";
    public static final String PREFIX_SCHEDULE = "sched:";
    public static final String PREFIX_AGENT = "agent:";
    public static final String PREFIX_POLL = "poll:";

    public static final String AGENT_OPEN = PREFIX_AGENT + "open";
    public static final String AGENT_TOGGLE = PREFIX_AGENT + "toggle";
    public static final String AGENT_LEADS = PREFIX_AGENT + "leads";
    public static final String AGENT_HELP = PREFIX_AGENT + "help";
    public static final String AGENT_FAQ = PREFIX_AGENT + "faq";
    public static final String AGENT_FAQ_SET = PREFIX_AGENT + "faqset";
    public static final String AGENT_OBJECTIONS = PREFIX_AGENT + "obj";
    public static final String AGENT_OBJECTIONS_SET = PREFIX_AGENT + "objset";
    public static final String AGENT_LEARNINGS = PREFIX_AGENT + "learn";

    public static final String AGENT_RADAR = PREFIX_AGENT + "radar";
    public static final String AGENT_RADAR_WATCHES = PREFIX_AGENT + "radar:watches";
    public static final String AGENT_RADAR_PLACES = PREFIX_AGENT + "radar:places";
    public static final String AGENT_RADAR_ADD_WATCH = PREFIX_AGENT + "radar:addwatch";
    public static final String AGENT_RADAR_ADD_PLACE = PREFIX_AGENT + "radar:addplace";
    public static final String AGENT_RADAR_RECHECK = PREFIX_AGENT + "radar:recheck:";

    public static final String AGENT_OUTREACH = PREFIX_AGENT + "outreach";
    public static final String AGENT_OUTREACH_NEW = PREFIX_AGENT + "outreach:new";
    public static final String AGENT_OUTREACH_SCENARIO = PREFIX_AGENT + "outreach:sc:";
    public static final String AGENT_OUTREACH_VIEW = PREFIX_AGENT + "outreach:view:";
    public static final String AGENT_OUTREACH_START = PREFIX_AGENT + "outreach:start:";
    public static final String AGENT_OUTREACH_PAUSE = PREFIX_AGENT + "outreach:pause:";
    public static final String AGENT_OUTREACH_IMPORT = PREFIX_AGENT + "outreach:import:";
    public static final String AGENT_OUTREACH_PARSE = PREFIX_AGENT + "outreach:parse:";

    public static final String AGENT_RADAR_HITS = PREFIX_AGENT + "radar:hits";
    public static final String AGENT_SCOUT_STATUS = PREFIX_AGENT + "scout:status";

    // Действия над лидом: agent:reply:<id>, agent:replyedit:<id>, agent:skip:<id>, agent:st:<STATUS>:<id>
    public static final String AGENT_REPLY = PREFIX_AGENT + "reply:";
    public static final String AGENT_REPLY_EDIT = PREFIX_AGENT + "replyedit:";
    public static final String AGENT_SKIP = PREFIX_AGENT + "skip:";
    public static final String AGENT_STATUS = PREFIX_AGENT + "st:";

    public static final String SCHEDULE_LIST = PREFIX_SCHEDULE + "list";

    public static final String PRODUCT_MENU = PREFIX_PRODUCT + "menu";
    public static final String PRODUCT_SYNC = PREFIX_PRODUCT + "sync";
    public static final String PRODUCT_REPORT = PREFIX_PRODUCT + "report";

    public static final String MENU_MAIN = PREFIX_MENU + "main";
    public static final String MENU_HOW_IT_WORKS = PREFIX_MENU + "howItWorks";
    public static final String MENU_WHAT_IN_REQUEST = PREFIX_MENU + "whatInRequest";

    public static final String CHANNEL_CONNECT = PREFIX_CHANNEL + "connect";
    public static final String CHANNEL_CONNECT_LIMITED = PREFIX_CHANNEL + "connect:limited";

    public static final String REQ_FREE = PREFIX_REQ + "free";
    public static final String REQ_START = PREFIX_REQ + "start";
    public static final String REQ_CONFIRM = PREFIX_REQ + "confirm";

    public static final String CHANNEL_CONNECT_PUBLISH = PREFIX_CHANNEL + "connectPublish";

    public static final String PAY_SELECT = PREFIX_PAY + "select";

    public static final String FEAT_POST_AUDIT = PREFIX_FEAT + "post";
    public static final String FEAT_COMPETITOR = PREFIX_FEAT + "competitor";
    public static final String FEAT_DIGEST = PREFIX_FEAT + "digest";
    public static final String FEAT_SELLING = PREFIX_FEAT + "selling";

    private CallbackData() {
    }
}
