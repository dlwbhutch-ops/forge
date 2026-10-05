package com.housecommander.spectator;

import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.SpectatorCardGroup;
import java.util.*;

/** One rendering algorithm for Android Canvas and Swing; no engine or platform objects. */
public final class BroadcastRenderer {
    public interface Painter {
        void box(int fill, int border, float stroke, float x, float y, float w, float h);
        void text(int color, float size, float x, float y, float maxWidth, String text);
        void image(LiveGameState.CardState card, float x, float y, float w, float h);
        void line(int color, float stroke, float... xy);
        void clip(float x, float y, float w, float h);
        void restore();
    }
    public static final class Hit {
        public final LiveGameState.CardState card;
        public final LiveGameState.PlayerState player;
        private final float x,y,w,h;
        private Hit(LiveGameState.CardState card, LiveGameState.PlayerState player,
                float x,float y,float w,float h) {
            this.card=card;this.player=player;this.x=x;this.y=y;this.w=w;this.h=h;
        }
        boolean contains(float px,float py) {return px>=x&&py>=y&&px<=x+w&&py<=y+h;}
    }
    private static final int BG=0xff101f28, PANEL=0xff20343e, TEXT=0xfff3eee0;
    private static final int MUTED=0xffb2c7ca, GOLD=0xffe1b95e, CYAN=0xff67d1d4, RED=0xffe58b78;
    private final List<Hit> hits=new ArrayList<>();
    private final Map<Integer,float[]> cardPoints=new HashMap<>();
    private final Map<String,float[]> playerPoints=new HashMap<>();
    private final Map<String,Long> eliminations=new HashMap<>();
    private final Set<String> lost=new HashSet<>();
    private long sequence=-1;
    private float hitScale=1;

    public Hit hit(float x,float y) {
        x/=hitScale;y/=hitScale;
        for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).contains(x,y))return hits.get(i);
        return null;
    }

    public boolean needsAnimation() {
        long now=System.currentTimeMillis();
        for(long time:eliminations.values())if(now-time<650)return true;
        return false;
    }

    public void draw(Painter p,float width,float height,LiveGameState state,BroadcastSettings settings) {
        hitScale=Math.min(1f,Math.max(.1f,height/500f));
        if(hitScale<1f){
            drawTable(new ScaledPainter(p,hitScale),width/hitScale,height/hitScale,state,settings);
        }else drawTable(p,width,height,state,settings);
    }

    private void drawTable(Painter p,float width,float height,LiveGameState state,BroadcastSettings settings) {
        if(state.sequence()<sequence){lost.clear();eliminations.clear();}
        if(state.sequence()!=sequence){
            for(LiveGameState.PlayerState player:state.players()){
                if(player.lost()&&lost.add(player.name()))eliminations.put(player.name(),System.currentTimeMillis());
            }
            sequence=state.sequence();
        }
        hits.clear();cardPoints.clear();playerPoints.clear();
        p.box(BG,BG,0,0,0,width,height);
        if(state.players().isEmpty()){
            p.text(TEXT,17,18,height/2,width-36,"Run & Watch to open the four-player table");return;
        }
        TournamentTableView table=new TournamentTableView(state);
        for(TournamentTableView.Seat seat:table.seats())drawSeat(p,width,height,seat,settings);
        float centerY=height*.445f;
        p.box(PANEL,0xff45616b,1, width*.01f,centerY,width*.98f,height*.11f);
        p.text(GOLD,Math.max(11,Math.min(15,width/65)),width*.025f,centerY+18,width*.95f,table.headline());
        String stack=state.stack().isEmpty()?"STACK EMPTY": "STACK "+state.stack().size()+" · "+state.stack().get(0);
        p.text(TEXT,Math.max(10,Math.min(13,width/70)),width*.025f,centerY+36,width*.95f,stack);
        if(settings.showArrows)drawLinks(p,width,height,state);
    }

    /** Compact landscape windows keep all four seats inside the viewport. */
    private static final class ScaledPainter implements Painter {
        private final Painter p;private final float scale;
        ScaledPainter(Painter p,float scale){this.p=p;this.scale=scale;}
        public void box(int fill,int border,float stroke,float x,float y,float w,float h){p.box(fill,border,stroke*scale,x*scale,y*scale,w*scale,h*scale);}
        public void text(int color,float size,float x,float y,float max,String value){p.text(color,size*scale,x*scale,y*scale,max*scale,value);}
        public void image(LiveGameState.CardState card,float x,float y,float w,float h){p.image(card,x*scale,y*scale,w*scale,h*scale);}
        public void line(int color,float stroke,float...xy){float[] out=xy.clone();for(int i=0;i<out.length;i++)out[i]*=scale;p.line(color,stroke*scale,out);}
        public void clip(float x,float y,float w,float h){p.clip(x*scale,y*scale,w*scale,h*scale);}
        public void restore(){p.restore();}
    }

    private void drawSeat(Painter p,float width,float height,TournamentTableView.Seat seat,BroadcastSettings settings){
        float x=width*seat.x,y=height*seat.y,w=width*seat.width,h=height*seat.height;
        boolean focus=seat.spotlight(settings);
        int border=seat.player.lost()?0xff65717a:(seat.priority&&settings.focusResponses?CYAN:(focus?GOLD:0xff42616d));
        Long transition=eliminations.get(seat.player.name());
        if(settings.animate&&transition!=null&&System.currentTimeMillis()-transition<650)border=RED;
        p.box(seat.player.lost()?0xff19262e:PANEL,border,focus?3:1,x,y,w,h);
        p.clip(x+4,y+4,w-8,h-8);
        float font=Math.max(10,Math.min(14,w/25));
        p.text(TEXT,font,x+10,y+19,w-20,seat.player.name()+" · "+seat.player.life()+" LIFE");
        p.text(border,Math.max(9,font-2),x+10,y+35,w-20,seat.status().isEmpty()?"PLAYER":seat.status());
        p.text(MUTED,Math.max(8,font-3),x+10,y+49,w-20,
                "Hand "+seat.player.handCount()+" · Library "+seat.player.libraryCount()+" · Poison "+seat.player.poison());
        playerPoints.put(seat.player.name(),new float[]{x+w/2,y+22});
        hits.add(new Hit(null,seat.player,x,y,w,h));
        boolean combat=false;
        for(SpectatorCardGroup group:seat.groups){if(group.card().attacking()||group.card().blocking()){combat=true;break;}}
        int rows=combat?4:3;
        float rowH=Math.max(20,(h-84)/rows);
        for(int category=0;category<rows;category++){
            int kind=combat?category:category+1;
            String label=kind==0?"COMBAT":kind==1?"CREATURES":kind==2?"LANDS":"OTHER";
            float ry=y+56+category*rowH;
            p.text(MUTED,8,x+9,ry+9,w-18,label);
            List<SpectatorCardGroup> groups=new ArrayList<>();
            for(SpectatorCardGroup group:seat.groups){if(category(group.card(),combat)==kind)groups.add(group);}
            float artH=Math.max(12,rowH-22);
            float scale=focus?TournamentLayoutSpec.ACTIVE_PLAYER_SCALE:TournamentLayoutSpec.INACTIVE_PLAYER_SCALE;
            float tileW=Math.min(90,Math.max(30,artH*.72f*scale));
            int capacity=Math.max(1,(int)((w-18)/(tileW+5)));
            int shown=groups.size()>capacity?Math.max(0,capacity-1):groups.size();
            for(int i=0;i<shown;i++){
                SpectatorCardGroup group=groups.get(i);float cx=x+9+i*(tileW+5),cy=ry+12;
                p.image(group.card(),cx,cy,tileW,artH);
                if(group.count()>1){
                    p.box(0xff14232c,CYAN,1,cx,cy,Math.min(tileW,35),14);
                    p.text(TEXT,9,cx+2,cy+11,tileW-4,"×"+group.count());
                }
                p.text(group.card().damage()>0?RED:TEXT,8,cx,cy+artH+9,tileW,
                        TournamentTableView.details(group.card(),group.count()));
                for(int id:group.cardIds())if(id>=0)cardPoints.put(id,new float[]{cx+tileW/2,cy+artH/2});
                hits.add(new Hit(group.card(),seat.player,cx,cy,tileW,artH+12));
            }
            if(groups.size()>shown){
                float moreX=x+9+shown*(tileW+5);
                p.text(CYAN,10,moreX,ry+25,tileW,"+"+(groups.size()-shown)+" piles");
                for(int i=shown;i<groups.size();i++){
                    for(int id:groups.get(i).cardIds())if(id>=0)cardPoints.put(id,new float[]{moreX+tileW/2,ry+20});
                }
            }
            else if(groups.isEmpty())p.text(MUTED,10,x+10,ry+26,w-20,"—");
        }
        p.text(MUTED,9,x+9,y+h-17,w-18,"Command: "+String.join(", ",seat.player.command())
                +" · Grave "+seat.player.graveyard().size()+" · Exile "+seat.player.exile().size());
        p.text(CYAN,8,x+9,y+h-5,w-18,seat.player.commanderDamage().isEmpty()
                ?"Tap a card to zoom · tap the panel for all piles": "Commander damage · "+String.join(", ",seat.player.commanderDamage()));
        p.restore();
    }

    private static int category(LiveGameState.CardState c,boolean combat){
        if(combat&&(c.attacking()||c.blocking()))return 0;
        return c.creature()?1:(c.land()?2:3);
    }
    private void drawLinks(Painter p,float width,float height,LiveGameState state){
        Set<String> drawn=new HashSet<>();
        for(LiveGameState.TargetLink link:state.links()){
            float[] from=cardPoints.get(link.sourceId);
            if(from==null&&link.kind==LiveGameState.TargetLink.Kind.TARGET)from=new float[]{width*.5f,height*.5f};
            float[] to=link.targetPlayer.isEmpty()?cardPoints.get(link.targetId):playerPoints.get(link.targetPlayer);
            if(from==null||to==null)continue;
            String key=Arrays.toString(from)+Arrays.toString(to)+link.kind;
            if(!drawn.add(key))continue;
            int color=link.kind==LiveGameState.TargetLink.Kind.ATTACK?RED:(link.kind==LiveGameState.TargetLink.Kind.BLOCK?CYAN:GOLD);
            double angle=Math.atan2(to[1]-from[1],to[0]-from[0]);
            p.line(color,2,from[0],from[1],to[0],to[1]);
            float a=(float)(angle+.5),b=(float)(angle-.5);
            p.line(color,2,to[0]-(float)Math.cos(a)*10,to[1]-(float)Math.sin(a)*10,
                    to[0],to[1],to[0]-(float)Math.cos(b)*10,to[1]-(float)Math.sin(b)*10);
        }
    }
}
