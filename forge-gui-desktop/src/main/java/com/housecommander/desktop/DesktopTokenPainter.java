package com.housecommander.desktop;

import com.housecommander.token.TokenArtResolver;
import java.awt.*;
import java.awt.geom.*;

final class DesktopTokenPainter implements TokenArtResolver.Painter {
    private final Graphics2D g;
    DesktopTokenPainter(Graphics2D g) { this.g = g; }
    public void polygon(int color, float... xy) {
        Path2D path = new Path2D.Float(); path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.closePath(); g.setColor(new Color(color, true)); g.fill(path);
    }
    public void circle(int color, float x, float y, float radius) {
        g.setColor(new Color(color, true)); g.fill(new Ellipse2D.Float(x-radius,y-radius,2*radius,2*radius));
    }
    public void rect(int color, float x, float y, float width, float height) {
        g.setColor(new Color(color, true)); g.fill(new Rectangle2D.Float(x,y,width,height));
    }
    public void line(int color, float width, float... xy) {
        Path2D path = new Path2D.Float(); path.moveTo(xy[0],xy[1]);
        for (int i=2;i<xy.length;i+=2) path.lineTo(xy[i],xy[i+1]);
        g.setColor(new Color(color,true)); g.setStroke(new BasicStroke(width,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g.draw(path);
    }
    public void text(int color, float size, float x, float y, String value) {
        g.setColor(new Color(color,true)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,Math.round(size)));
        g.drawString(value,x-g.getFontMetrics().stringWidth(value)/2f,y);
    }
}
