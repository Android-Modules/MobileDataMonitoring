package com.dalong.dataguard;
import org.json.*;
public final class RootMain {
    public static void main(String[] args) {
        try {
            android.os.Looper.prepareMainLooper();
            if(args.length>0&&args[0].equals("daemon")){new GuardDaemon(Integer.parseInt(args[1])).run();return;}
            if(args.length>0&&args[0].equals("request")){System.out.println(RootClient.request(new JSONObject(args[1])));return;}
            Platform p=new Platform();
            JSONObject o=new JSONObject().put("cards",p.cards()).put("defaultSub",p.defaultSub()).put("activeSub",p.activeSub());
            o.put("usage",p.usage(p.defaultSub(),System.currentTimeMillis()-3600000,System.currentTimeMillis()));
            o.put("counters",new JSONObject(p.counters()));
            System.out.println(o);
        } catch(Throwable e){ System.out.println("{\"error\":\""+e.getClass().getSimpleName()+"\"}");e.printStackTrace(System.err);System.exit(1); }
    }
}
