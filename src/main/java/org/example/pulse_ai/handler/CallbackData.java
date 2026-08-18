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
    public static final String AGENT_SALES = PREFIX_AGENT + "sales";
    public static final String AGENT_GROWTH = PREFIX_AGENT + "growth";
    public static final String AGENT_SETTINGS = PREFIX_AGENT + "settings";

    public static final String AGENT_RADAR = PREFIX_AGENT + "radar";
    public static final String AGENT_RADAR_MATCH = PREFIX_AGENT + "radar:match";
    public static final String AGENT_RADAR_WATCHES = PREFIX_AGENT + "radar:watches";
    public static final String AGENT_RADAR_PLACES = PREFIX_AGENT + "radar:places";
    public static final String AGENT_RADAR_ADD_WATCH = PREFIX_AGENT + "radar:addwatch";
    public static final String AGENT_RADAR_ADD_PLACE = PREFIX_AGENT + "radar:addplace";
    public static final String AGENT_RADAR_RECHECK = PREFIX_AGENT + "radar:recheck:";
    public static final String AGENT_RADAR_VIEW = PREFIX_AGENT + "radar:view:";
    public static final String AGENT_RADAR_CREATIVE = PREFIX_AGENT + "radar:creative:";
    public static final String AGENT_RADAR_BOOK = PREFIX_AGENT + "radar:book:";
    public static final String AGENT_RADAR_HITS = PREFIX_AGENT + "radar:hits";

    public static final String AGENT_DEAL = PREFIX_AGENT + "deal:";
    public static final String AGENT_DEAL_LIST = PREFIX_AGENT + "deal:list";
    public static final String AGENT_DEAL_OPEN = PREFIX_AGENT + "deal:open:";
    public static final String AGENT_DEAL_FMT = PREFIX_AGENT + "deal:fmt:";
    public static final String AGENT_DEAL_VIEW = PREFIX_AGENT + "deal:view:";
    public static final String AGENT_DEAL_CREATIVE = PREFIX_AGENT + "deal:cr:";
    public static final String AGENT_DEAL_BRIEF = PREFIX_AGENT + "deal:brief:";
    public static final String AGENT_DEAL_SENT = PREFIX_AGENT + "deal:sent:";
    public static final String AGENT_DEAL_OK = PREFIX_AGENT + "deal:ok:";
    public static final String AGENT_DEAL_NO = PREFIX_AGENT + "deal:no:";
    public static final String AGENT_DEAL_PRICE = PREFIX_AGENT + "deal:price:";

    public static final String AGENT_OUTREACH = PREFIX_AGENT + "outreach";
    public static final String AGENT_OUTREACH_NEW = PREFIX_AGENT + "outreach:new";
    public static final String AGENT_OUTREACH_SCENARIO = PREFIX_AGENT + "outreach:sc:";
    public static final String AGENT_OUTREACH_VIEW = PREFIX_AGENT + "outreach:view:";
    public static final String AGENT_OUTREACH_START = PREFIX_AGENT + "outreach:start:";
    public static final String AGENT_OUTREACH_PAUSE = PREFIX_AGENT + "outreach:pause:";
    public static final String AGENT_OUTREACH_IMPORT = PREFIX_AGENT + "outreach:import:";
    public static final String AGENT_OUTREACH_PARSE = PREFIX_AGENT + "outreach:parse:";
    public static final String AGENT_OUTREACH_REPLIES = PREFIX_AGENT + "outreach:replies:";

    public static final String AGENT_PARSE = PREFIX_AGENT + "parse";
    public static final String AGENT_PARSE_SRC = PREFIX_AGENT + "parse:src:";
    public static final String AGENT_PARSE_LINK = PREFIX_AGENT + "parse:link";

    public static final String AGENT_SCOUT_STATUS = PREFIX_AGENT + "scout:status";
    public static final String AGENT_SCOUT_ADMIN = PREFIX_AGENT + "scout:admin";
    public static final String AGENT_SCOUT_LOGS = PREFIX_AGENT + "scout:logs";
    public static final String AGENT_SCOUT_TEMPLATES = PREFIX_AGENT + "scout:tpl";
    public static final String AGENT_SCOUT_KEYWORDS = PREFIX_AGENT + "scout:kw";
    public static final String AGENT_SCOUT_PAUSE = PREFIX_AGENT + "scout:pause:";
    public static final String AGENT_SCOUT_RESUME = PREFIX_AGENT + "scout:resume:";
    public static final String AGENT_SCOUT_CAMPAIGN_PAUSE = PREFIX_AGENT + "scout:cpause:";

    // Действия над лидом: agent:reply:<id>, agent:replyedit:<id>, agent:skip:<id>, agent:st:<STATUS>:<id>
    public static final String AGENT_REPLY = PREFIX_AGENT + "reply:";
    public static final String AGENT_REPLY_EDIT = PREFIX_AGENT + "replyedit:";
    public static final String AGENT_SKIP = PREFIX_AGENT + "skip:";
    public static final String AGENT_STATUS = PREFIX_AGENT + "st:";

    public static final String SCHEDULE_LIST = PREFIX_SCHEDULE + "list";

    public static final String PRODUCT_MENU = PREFIX_PRODUCT + "menu";
    public static final String PRODUCT_SYNC = PREFIX_PRODUCT + "sync";
    public static final String PRODUCT_REPORT = PREFIX_PRODUCT + "report";
    public static final String PRODUCT_RELEASES = PREFIX_PRODUCT + "releases";
    public static final String PRODUCT_RELEASE_ADD = PREFIX_PRODUCT + "reladd";
    public static final String PRODUCT_RELEASE_LATEST = PREFIX_PRODUCT + "rellatest";
    public static final String PRODUCT_STORY = PREFIX_PRODUCT + "story";
    public static final String PRODUCT_STORY_BUILD = PREFIX_PRODUCT + "story:build";
    public static final String PRODUCT_STORY_NEXT = PREFIX_PRODUCT + "story:next";
    public static final String PRODUCT_STORY_START = PREFIX_PRODUCT + "story:start";
    public static final String PRODUCT_STORY_SHOW = PREFIX_PRODUCT + "story:show";

    public static final String MENU_MAIN = PREFIX_MENU + "main";
    public static final String MENU_HOW_IT_WORKS = PREFIX_MENU + "howItWorks";
    public static final String MENU_WHAT_IN_REQUEST = PREFIX_MENU + "whatInRequest";
    public static final String MENU_CONTENT = PREFIX_MENU + "content";
    public static final String MENU_ANALYTICS = PREFIX_MENU + "analytics";
    public static final String MENU_SETTINGS = PREFIX_MENU + "settings";
    public static final String MENU_HELP = PREFIX_MENU + "help";
    public static final String MENU_MORE = PREFIX_MENU + "more";
    public static final String MENU_GROWTH = PREFIX_MENU + "growth";
    public static final String MENU_ANALYTICS_PLUS = PREFIX_MENU + "analyticsplus";
    public static final String MENU_TIMEZONE = PREFIX_MENU + "tz";
    public static final String MENU_TIMEZONE_SET = PREFIX_MENU + "tzset:";
    public static final String MENU_STYLE_PROMPT = PREFIX_MENU + "style";
    public static final String MENU_STYLE_PROMPT_SET = PREFIX_MENU + "styleset";
    public static final String MENU_STYLE_PROMPT_CLEAR = PREFIX_MENU + "styleclear";

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
