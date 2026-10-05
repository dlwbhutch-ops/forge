package com.housecommander.lab;

import android.graphics.*;
import com.housecommander.token.TokenArtResolver;

final class AndroidTokenPainter implements TokenArtResolver.Painter {
    private final Canvas canvas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    AndroidTokenPainter(Canvas canvas) { this.canvas = canvas; }
    private void fill(int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); }
    public void polygon(int color, float... xy) {
        Path path = new Path();path.moveTo(xy[0],xy[1]);
        for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);
        path.close();fill(color);canvas.drawPath(path,paint);
    }
    public void circle(int color,float x,float y,float radius) { fill(color);canvas.drawCircle(x,y,radius,paint); }
    public void rect(int color,float x,float y,float width,float height) { fill(color);canvas.drawRect(x,y,x+width,y+height,paint); }
    public void line(int color,float width,float... xy) {
        Path path=new Path();path.moveTo(xy[0],xy[1]);
        for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(path,paint);
    }
    public void text(int color,float size,float x,float y,String value) {
        fill(color);paint.setTextSize(size);paint.setTypeface(Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(value,x,y,paint);
    }
}
