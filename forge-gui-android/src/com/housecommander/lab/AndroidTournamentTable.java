package com.housecommander.lab;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.spectator.BroadcastRenderer;
import com.housecommander.spectator.BroadcastSettings;
import java.util.function.Consumer;

final class AndroidTournamentTable extends View {
    private final BroadcastRenderer renderer=new BroadcastRenderer();
    private final AndroidCardArtCache art;
    private final BroadcastSettings settings;
    private final Consumer<LiveGameState.CardState> zoom;
    private final Consumer<LiveGameState.PlayerState> inspect;
    private LiveGameState state=LiveGameState.idle();
    AndroidTournamentTable(Context context,AndroidCardArtCache art,BroadcastSettings settings,
            Consumer<LiveGameState.CardState> zoom,Consumer<LiveGameState.PlayerState> inspect){
        super(context);this.art=art;this.settings=settings;this.zoom=zoom;this.inspect=inspect;
        setContentDescription("Overhead Commander table. Four players, active turn, priority, stack and combat.");
        setFocusable(true);setClickable(true);
    }
    void update(LiveGameState next){state=next;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        canvas.save();canvas.scale(density,density);
        renderer.draw(new Painter(canvas),getWidth()/density,getHeight()/density,state,settings);
        canvas.restore();
        if(settings.animate&&renderer.needsAnimation())postInvalidateDelayed(45);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_UP){
            performClick();float density=getResources().getDisplayMetrics().density;
            BroadcastRenderer.Hit hit=renderer.hit(event.getX()/density,event.getY()/density);
            if(hit!=null){if(hit.card!=null)zoom.accept(hit.card);else inspect.accept(hit.player);}return true;
        }
        return event.getAction()==MotionEvent.ACTION_DOWN||super.onTouchEvent(event);
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private final class Painter implements BroadcastRenderer.Painter{
        private final Canvas canvas;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Painter(Canvas canvas){this.canvas=canvas;}
        public void box(int fill,int border,float stroke,float x,float y,float w,float h){
            RectF r=new RectF(x,y,x+w,y+h);paint.setStyle(Paint.Style.FILL);paint.setColor(fill);canvas.drawRoundRect(r,14,14,paint);
            if(stroke>0){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(border);canvas.drawRoundRect(r,14,14,paint);}
        }
        public void text(int color,float size,float x,float y,float maxWidth,String value){
            paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size);
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD));paint.setTextAlign(Paint.Align.LEFT);
            String text=value;while(text.length()>1&&paint.measureText(text)>maxWidth)text=text.substring(0,text.length()-2)+"…";
            canvas.drawText(text,x,y,paint);
        }
        public void image(LiveGameState.CardState card,float x,float y,float w,float h){
            float density=getResources().getDisplayMetrics().density;
            Bitmap image=art.cardBitmap(card,Math.max(1,(int)(w*density)),Math.max(1,(int)(h*density)),AndroidTournamentTable.this::invalidate);
            paint.setStyle(Paint.Style.FILL);
            if(image!=null&&!image.isRecycled()){
                float iw=image.getWidth()/density,ih=image.getHeight()/density;
                canvas.drawBitmap(image,null,new RectF(x+(w-iw)/2,y+(h-ih)/2,x+(w+iw)/2,y+(h+ih)/2),paint);
            }
            else{box(0xff3c5660,0xff72838a,1,x,y,w,h);text(0xfff3eee0,9,x+3,y+h/2,w-6,card.name());}
        }
        public void line(int color,float stroke,float...xy){
            Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(path,paint);
        }
        public void clip(float x,float y,float w,float h){canvas.save();canvas.clipRect(x,y,x+w,y+h);}
        public void restore(){canvas.restore();}
    }
}
