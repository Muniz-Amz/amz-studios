package com.amzstudios.cofre;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;

/** Native shapes and small paths: no fonts, image library, blur or continuous animation. */
final class VaultUi {
    static final int BG=0xff0d141b, SURFACE=0xff17212b, BORDER=0xff2b3945,
            INK=0xffedf4f7, MUTED=0xffa5b6c5, ACCENT=0xff9be5d0;
    static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    static GradientDrawable shape(Context c,int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;
    }
    static Drawable ripple(Context c,int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x229be5d0),shape(c,color,radius),shape(c,0xffffffff,radius));}
    static Drawable icon(Context c,String name,int color,int size){
        int resource;
        switch(name){
            case "folder": resource=R.drawable.vault_folder;break;
            case "image": resource=R.drawable.vault_image;break;
            case "video": resource=R.drawable.vault_video;break;
            case "audio": resource=R.drawable.vault_audio;break;
            case "file": resource=R.drawable.vault_file;break;
            case "lock": resource=R.drawable.vault_lock;break;
            case "shield": resource=R.drawable.vault_shield;break;
            case "search": resource=R.drawable.vault_search;break;
            case "grid": resource=R.drawable.vault_grid;break;
            case "list": resource=R.drawable.vault_list;break;
            case "trash": resource=R.drawable.vault_trash;break;
            case "more": resource=R.drawable.vault_more;break;
            case "plus": resource=R.drawable.vault_plus;break;
            case "arrow": resource=R.drawable.vault_arrow;break;
            case "back": resource=R.drawable.vault_back;break;
            case "close": resource=R.drawable.vault_close;break;
            case "check": resource=R.drawable.vault_check;break;
            case "play": resource=R.drawable.vault_play;break;
            case "pause": resource=R.drawable.vault_pause;break;
            case "backup": resource=R.drawable.vault_backup;break;
            case "drive": resource=R.drawable.vault_drive;break;
            default: resource=R.drawable.vault_circle;
        }
        Drawable vector=c.getDrawable(resource).mutate();vector.setTint(color);return new SizedIcon(vector,dp(c,size));
    }
    private static final class SizedIcon extends Drawable {
        private final Drawable vector;private final int size;
        SizedIcon(Drawable vector,int size){this.vector=vector;this.size=size;setBounds(0,0,size,size);}
        @Override public void draw(Canvas c){vector.setBounds(getBounds());vector.draw(c);}
        @Override public void setAlpha(int a){vector.setAlpha(a);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter f){vector.setColorFilter(f);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
    }
}
