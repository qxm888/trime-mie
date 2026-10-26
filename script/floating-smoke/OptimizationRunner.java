package dev.codex.trime.smoke;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.EditText;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Uses only generated fixtures and Trime's own test editor; preserves user layouts in finally. */
public final class OptimizationRunner extends Instrumentation {
    final Bundle result = new Bundle();
    Object custom, manager, service, controller;
    String saved, mode, active;
    File image;
    final String first = "custom_codex_optimization_letters", second = "custom_codex_optimization_numbers";
    interface Action { void run() throws Exception; }
    void main(Action a) throws Exception { Throwable[] e={null};runOnMainSync(()->{try{a.run();}catch(Throwable t){e[0]=t;}});if(e[0]!=null)throw new Exception(e[0]); }
    Class<?> cls(String n)throws Exception{return getTargetContext().getClassLoader().loadClass(n);}
    Object obj(String n)throws Exception{return cls(n).getField("INSTANCE").get(null);}
    Object field(Object o,String n)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(n);}
    void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    Object invoke(Object o,String n,Class<?>[] t,Object...a)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(o,a);}catch(NoSuchMethodException e){c=c.getSuperclass();}}throw new NoSuchMethodException(n);}
    Object call(Object o,String n)throws Exception{return invoke(o,n,new Class<?>[0]);}
    void check(boolean b,String m)throws Exception{if(!b)throw new Exception(m);}
    Object window()throws Exception{return call(field(service,"inputView"),"getKeyboardWindow");}
    Object visible()throws Exception{return call(cls("com.osfans.trime.ime.keyboard.KeyboardWindow").getField("Companion").get(null),"getCurrentKeyboard");}
    void action(int index)throws Exception {
        ViewGroup view=(ViewGroup)call(window(),"getCurrentKeyboardView");
        Object key=((List<?>)call(visible(),"getKeys")).get(index);
        Object click=Enum.valueOf((Class)cls("com.osfans.trime.ime.keyboard.KeyBehavior"),"CLICK");
        Object action=invoke(key,"getAction",new Class<?>[]{cls("com.osfans.trime.ime.keyboard.KeyBehavior")},click);
        invoke(view.getChildAt(index),"processKeyAction",new Class<?>[]{cls("com.osfans.trime.ime.keyboard.KeyAction"),cls("com.osfans.trime.ime.keyboard.KeyBehavior")},action,click);
    }

    void removeOwned(File file){if(file.isDirectory()){File[] files=file.listFiles();if(files!=null)for(File child:files)removeOwned(child);}file.delete();}
    void storageRegression() throws Exception {
        Object prefsObject=call(cls("com.osfans.trime.data.prefs.AppPrefs").getField("Companion").get(null),"defaultInstance");
        android.content.SharedPreferences prefs=(android.content.SharedPreferences)field(prefsObject,"shared");
        if(!"DIRECT_EXTERNAL".equals(prefs.getString("profile_data_storage_mode",""))) {result.putString("storage","not in direct mode; file-layer regression covered by JVM tests");return;}
        final String oldPath=prefs.getString("profile_direct_external_path","");
        final boolean oldReady=prefs.getBoolean("profile_direct_external_ready",false);
        Object storage=obj("com.osfans.trime.core.data.DirectExternalStorage"), daemon=obj("com.osfans.trime.daemon.RimeDaemon");
        File resources=(File)invoke(storage,"resources",new Class<?>[]{Context.class},getTargetContext());
        File stage=new File(resources,"optimization-migration-"+UUID.randomUUID());check(stage.mkdir(),"Cannot create isolated migration fixture");
        File sourceAssets=new File(stage,"mie_resources/"+getTargetContext().getPackageName()+"/custom_kb_bg");check(sourceAssets.mkdirs(),"Cannot prepare asset fixture");
        File picture=new File(sourceAssets,"migration-fixture.png");
        try(FileOutputStream out=new FileOutputStream(picture)){out.write(new byte[]{1,2,3,4});}
        try(FileOutputStream out=new FileOutputStream(new File(stage,"migration-fixture.txt"))){out.write(new byte[]{4,3,2,1});}
        final File target=(File)call(obj("com.osfans.trime.core.data.UserDataManager"),"getAppUserDataDir");
        final Set<String> priorData=new HashSet<>(Arrays.asList(target.getParentFile().list()));
        final Set<String> priorFiles=new HashSet<>(Arrays.asList(getTargetContext().getFilesDir().list()));
        final String before=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);
        final boolean[] completed={false};
        Object closure=Proxy.newProxyInstance(getTargetContext().getClassLoader(),new Class<?>[]{cls("kotlin.jvm.functions.Function0")},(proxy,method,args)->{
            if(!method.getName().equals("invoke"))return null;
            try {
                // A session callback on main must not wait for the entire migration lock.
                main(()->{invoke(daemon,"createSession",new Class<?>[]{String.class},"codex-migration-check");invoke(daemon,"destroySession",new Class<?>[]{String.class},"codex-migration-check");});
                check(prefs.edit().putString("profile_direct_external_path",stage.getAbsolutePath()).commit(),"Cannot select fixture path");
                org.json.JSONObject json=new org.json.JSONObject(before);
                json.getJSONObject("keyboards").getJSONObject(first).put("background",new org.json.JSONObject().put("type","IMAGE").put("image",picture.getAbsolutePath()));
                invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},json.toString(),true);
                Object appMode=Enum.valueOf((Class)cls("com.osfans.trime.core.data.DataStorageMode"),"APP_STORAGE");
                invoke(storage,"deactivate",new Class<?>[]{Context.class,cls("com.osfans.trime.core.data.DataStorageMode")},getTargetContext(),appMode);
                completed[0]=true;
                check("APP_STORAGE".equals(prefs.getString("profile_data_storage_mode","")),"Mode was not committed");
                check(new File(target,"migration-fixture.txt").length()==4,"Complete user-data file was not copied");
                File copied=new File(getTargetContext().getFilesDir(),"custom_kb_bg/migration-fixture.png");
                check(copied.length()==4,"Media was not migrated to private storage");
                check(((String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L)).contains(copied.getAbsolutePath()),"Media path was not remapped");
                check(picture.exists(),"External source was deleted");
                result.putString("storage","isolated direct-to-app copy, media path remap, original-source retention and main-thread session callback passed; original directories restored");
            } finally {
                check(prefs.edit().putString("profile_direct_external_path",oldPath).putBoolean("profile_direct_external_ready",oldReady).putString("profile_data_storage_mode","DIRECT_EXTERNAL").commit(),"Cannot restore directory choice");
                invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},before,true);check((Boolean)call(custom,"flush"),"Cannot restore layouts after storage check");
                if(completed[0]) {
                    removeOwned(target);
                    for(File candidate:target.getParentFile().listFiles())if(!priorData.contains(candidate.getName())&&candidate.getName().startsWith("rime-before-switch-"))check(candidate.renameTo(target),"Cannot restore prior app data directory");
                    removeOwned(new File(getTargetContext().getFilesDir(),"custom_kb_bg"));
                    for(File candidate:getTargetContext().getFilesDir().listFiles())if(!priorFiles.contains(candidate.getName())&&candidate.getName().startsWith("custom_kb_bg-before-switch-"))check(candidate.renameTo(new File(getTargetContext().getFilesDir(),"custom_kb_bg")),"Cannot restore prior private media");
                }
            }
            return cls("kotlin.Unit").getField("INSTANCE").get(null);
        });
        try {invoke(daemon,"changeStorage",new Class<?>[]{cls("kotlin.jvm.functions.Function0")},closure);}
        finally {removeOwned(stage);}
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){int status=Activity.RESULT_CANCELED;
        try {
            Activity activity=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->invoke(field(activity,"testInputPanel"),"show",new Class<?>[]{Window.class},activity.getWindow()));
            for(int i=0;i<80&&service==null;i++) {main(()->{Field f=cls("com.osfans.trime.ime.keyboard.KeyboardWindow").getDeclaredField("instance");f.setAccessible(true);Object w=f.get(null);if(w!=null)service=call(w,"getService");});SystemClock.sleep(100);}
            check(service!=null,"IME missing");
            custom=obj("com.osfans.trime.data.keymap.CustomKeyboards");manager=obj("com.osfans.trime.data.theme.ThemeManager");
            saved=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);mode=(String)call(custom,"getMode");active=(String)call(custom,"getActiveId");
            String fixture="{\"format\":\"trime-custom-keyboards\",\"keyboards\":{\""+first+"\":{\"name\":\"optimization letters\",\"importedSkin\":{\"page\":\"en_26\",\"variants\":{}},\"rows\":[{\"keys\":[{\"label\":\"Shift\",\"width\":20,\"bindings\":{\"click\":\"Shift_L\"}},{\"label\":\"a\",\"width\":40,\"bindings\":{\"click\":\"a\"}},{\"label\":\"123\",\"width\":40,\"bindings\":{\"click\":\"Keyboard_"+second+"\"}}]}]},\""+second+"\":{\"name\":\"optimization numbers\",\"importedSkin\":{\"page\":\"num_9\",\"variants\":{}},\"rows\":[{\"keys\":[{\"label\":\"1\",\"width\":50,\"bindings\":{\"click\":\"1\"}},{\"label\":\"ABC\",\"width\":50,\"bindings\":{\"click\":\"Keyboard_"+first+"\"}}]}]}}}";
            main(()->{invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},fixture,false);invoke(custom,"setActiveId",new Class<?>[]{String.class},first);invoke(custom,"setMode",new Class<?>[]{String.class},"custom");call(manager,"requestCustomizationRefresh");});
            boolean ready=false;
            for(int i=0;i<100&&!ready;i++){SystemClock.sleep(100);boolean[] ok={false};main(()->{Object kb=call(window(),"getActiveKeyboard");if(kb!=null)ok[0]=first.equals(invoke(custom,"resolveId",new Class<?>[]{String.class},call(kb,"getKeyboardId")));});ready=ok[0];}
            check(ready,"Fixture keyboard not attached");
            final EditText input=(EditText)field(field(activity,"testInputPanel"),"input");
            main(()->{input.getText().clear();service.getClass().getMethod("toggleFloatingKeyboard").invoke(service);controller=field(service,"inputView");check((Boolean)call(controller,"isFloatingKeyboardEnabled"),"Floating surface missing");});
            SystemClock.sleep(400);
            main(()->{
                action(0);
                check((Boolean)call(visible(),"isShifted"),"Floating Shift did not latch");
                Object companion=cls("com.osfans.trime.ime.keyboard.KeyboardWindow").getField("Companion").get(null);
                check(call(companion,"getCurrentKeyboard")==visible(),"Action source is still the hidden docked keyboard");
                action(1);
            });
            SystemClock.sleep(500);
            main(()->check("A".equals(input.getText().toString()),"Shift+a did not commit uppercase A in the Trime test editor"));
            main(()->action(2));SystemClock.sleep(400);
            main(()->check(second.equals(call(visible(),"getKeyboardId")),"Number-page switch did not update floating keyboard"));
            main(()->action(1));SystemClock.sleep(400);
            main(()->{
                check(first.equals(call(visible(),"getKeyboardId")),"Return to letters failed");action(0);
                call(controller,"beginFloatingResize");
                invoke(controller,"updateFloatingResize",new Class<?>[]{float.class,float.class},60f,40f);
                call(controller,"endFloatingResize");
                check((Boolean)call(visible(),"isShifted"),"Resize lost Shift state");
                call(service,"toggleFloatingKeyboard");
                check((Boolean)call(call(window(),"getActiveKeyboard"),"isShifted"),"Docking lost modifier state");
            });
            result.putString("floating_state","Shift+a commits A; letter/number/letter switch, resize and docking preserve visible state");

            // A path that cannot be committed must return false and preserve the original file.
            main(()->{
                synchronized(field(custom,"lock")) {
                    call(custom,"flush");
                    File config=new File(getTargetContext().getFilesDir(),"custom_keyboards.json");
                    File backup=new File(getTargetContext().getCacheDir(),"optimization-config-backup.json");
                    String before=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);
                    check(config.renameTo(backup),"Cannot stage configuration failure check");
                    try {check(config.mkdir(),"Cannot stage blocked destination");invoke(custom,"rename",new Class<?>[]{String.class,String.class},first,"temporary name");check(!(Boolean)call(custom,"flush"),"Persistence failure was reported as success");}
                    finally {check(config.delete(),"Cannot remove test-only blocked destination");check(backup.renameTo(config),"Cannot restore original configuration");invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},before,true);check((Boolean)call(custom,"flush"),"Configuration recovery failed");}
                }
            });
            result.putString("persistence","blocked destination reports failure; original complete configuration restored");
            storageRegression();

            image=new File(getTargetContext().getCacheDir(),"optimization-large-atlas.png");
            Bitmap generated=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888);generated.eraseColor(Color.MAGENTA);
            try(FileOutputStream out=new FileOutputStream(image)){generated.compress(Bitmap.CompressFormat.PNG,100,out);}generated.recycle();
            Object assets=obj("com.osfans.trime.data.theme.BitmapAssets");
            CountDownLatch loaded=new CountDownLatch(1);
            Object observer=Proxy.newProxyInstance(getTargetContext().getClassLoader(),new Class<?>[]{cls("com.osfans.trime.data.theme.BitmapAssets$Observer")},(proxy,method,args)->{if(method.getName().equals("onImageReady"))loaded.countDown();return null;});
            long[] elapsed={0};
            main(()->{long started=SystemClock.elapsedRealtime();Object immediate=invoke(assets,"request",new Class<?>[]{String.class,cls("com.osfans.trime.data.theme.BitmapAssets$Observer"),int.class},image.getAbsolutePath(),observer,4194304);elapsed[0]=SystemClock.elapsedRealtime()-started;check(immediate==null,"Uncached image decoded synchronously on UI thread");});
            check(loaded.await(8,TimeUnit.SECONDS),"Background decode callback missing");
            Object decoded=invoke(assets,"load",new Class<?>[]{String.class,int.class},image.getAbsolutePath(),4194304);
            Bitmap pixels=(Bitmap)call(decoded,"getBitmap");
            check((Integer)call(decoded,"getSourceWidth")==3000&&(Integer)call(decoded,"getSourceHeight")==2000,"Original atlas coordinate dimensions lost");
            check((long)pixels.getWidth()*pixels.getHeight()<=4194304,"Decode exceeded pixel budget");
            check(pixels.getPixel(0,0)==Color.MAGENTA,"Background decode changed pixels");
            result.putString("bitmap","3000x2000 atlas decoded in background to "+pixels.getWidth()+"x"+pixels.getHeight()+"; UI request="+elapsed[0]+"ms");
            main(()->call(assets,"invalidate"));
            Bitmap replaced=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);replaced.eraseColor(Color.GREEN);try(FileOutputStream out=new FileOutputStream(image)){replaced.compress(Bitmap.CompressFormat.PNG,100,out);}replaced.recycle();
            decoded=invoke(assets,"load",new Class<?>[]{String.class,int.class},image.getAbsolutePath(),4194304);
            check(((Bitmap)call(decoded,"getBitmap")).getPixel(0,0)==Color.GREEN,"Refresh did not pick up an image replaced at the same path");
            Activity stats=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.InputStatsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->{Object snapshot=field(stats,"lastSnapshot");Object daily=call(snapshot,"getDaily");invoke(daily,"setCount",new Class<?>[]{int.class},1);invoke(daily,"setLength",new Class<?>[]{int.class},1);String report=(String)invoke(stats,"buildReport",new Class<?>[]{boolean.class},true);check(!report.isEmpty(),"Long report empty");stats.finish();});
            result.putString("report","non-empty long report generated without missing-format-argument exception");
            status=Activity.RESULT_OK;result.putString("result","PASS");
        } catch(Throwable e) {result.putString("result","FAIL: "+android.util.Log.getStackTraceString(e));}
        finally {
            try {
                main(()->{if(saved!=null){invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},saved,true);invoke(custom,"setMode",new Class<?>[]{String.class},mode);invoke(custom,"setActiveId",new Class<?>[]{String.class},active);call(custom,"flush");call(manager,"requestCustomizationRefresh");}});
                if(image!=null)image.delete();
            } catch(Throwable e){status=Activity.RESULT_CANCELED;result.putString("restore_error",android.util.Log.getStackTraceString(e));}
        }
        finish(status,result);
    }
}
