package com.housecommander.desktop;

import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.spectator.BroadcastRenderer;
import com.housecommander.spectator.BroadcastSettings;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayDeque;
import java.util.function.Consumer;

final class DesktopTournamentTable extends JPanel {
    private final BroadcastRenderer renderer=new BroadcastRenderer();
    private final DesktopCardArtCache art;
    private final BroadcastSettings settings;
    private LiveGameState state=LiveGameState.idle();
    private final Timer animation;
    DesktopTournamentTable(DesktopCardArtCache art,BroadcastSettings settings,
            Consumer<LiveGameState.CardState> zoom,Consumer<LiveGameState.PlayerState> inspect){
        this.art=art;this.settings=settings;setPreferredSize(new Dimension(1100,640));
        setMinimumSize(new Dimension(440,430));
        animation=new Timer(45,e->{repaint();if(!renderer.needsAnimation())((Timer)e.getSource()).stop();});
        addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){
            BroadcastRenderer.Hit hit=renderer.hit(e.getX(),e.getY());
            if(hit!=null){if(hit.card!=null)zoom.accept(hit.card);else inspect.accept(hit.player);}
        }});
    }
    void update(LiveGameState next){state=next;repaint();if(settings.animate)animation.start();}
    @Override public void removeNotify(){animation.stop();super.removeNotify();}
    @Override protected void paintComponent(Graphics graphics){
        super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();
        try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            renderer.draw(new Painter(g),getWidth(),getHeight(),state,settings);
        }finally{g.dispose();}
    }
    private final class Painter implements BroadcastRenderer.Painter{
        private final Graphics2D g;private final ArrayDeque<Shape> clips=new ArrayDeque<>();
        Painter(Graphics2D g){this.g=g;}
        public void box(int fill,int border,float stroke,float x,float y,float w,float h){
            Shape s=new RoundRectangle2D.Float(x,y,w,h,14,14);g.setColor(new Color(fill,true));g.fill(s);
            if(stroke>0){g.setColor(new Color(border,true));g.setStroke(new BasicStroke(stroke));g.draw(s);}
        }
        public void text(int color,float size,float x,float y,float maxWidth,String value){
            g.setColor(new Color(color,true));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,Math.round(size)));
            String text=value;
            while(text.length()>1&&g.getFontMetrics().stringWidth(text)>maxWidth)text=text.substring(0,text.length()-2)+"…";
            g.drawString(text,x,y);
        }
        public void image(LiveGameState.CardState card,float x,float y,float w,float h){
            ImageIcon image=art.cardIcon(card,Math.max(1,(int)w),Math.max(1,(int)h),DesktopTournamentTable.this::repaint);
            if(image!=null){g.drawImage(image.getImage(),Math.round(x+(w-image.getIconWidth())/2),Math.round(y+(h-image.getIconHeight())/2),null);}
            else{box(0xff3c5660,0xff72838a,1,x,y,w,h);text(0xfff3eee0,9,x+3,y+h/2,w-6,card.name());}
        }
        public void line(int color,float stroke,float...xy){
            Path2D path=new Path2D.Float();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);
            g.setColor(new Color(color,true));g.setStroke(new BasicStroke(stroke,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(path);
        }
        public void clip(float x,float y,float w,float h){
            Shape old=g.getClip();clips.push(old==null?new Rectangle(0,0,getWidth(),getHeight()):old);g.clip(new Rectangle2D.Float(x,y,w,h));
        }
        public void restore(){g.setClip(clips.pop());}
    }
}
