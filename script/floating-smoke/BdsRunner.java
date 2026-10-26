package dev.codex.trime.smoke;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises only the supplied skin fixtures and Trime-owned input views. No screen capture. */
@android.annotation.TargetApi(29)
public final class BdsRunner extends Instrumentation {
    Bundle result=new Bundle(); Object custom,manager,service; String saved,mode,active;
    android.content.SharedPreferences prefs; String storage; boolean hadStorage;
    Set<String> oldFolders=new HashSet<>();
    interface Action{void run()throws Exception;}
    void main(Action a)throws Exception{Throwable[] e={null};runOnMainSync(()->{try{a.run();}catch(Throwable t){e[0]=t;}});if(e[0]!=null)throw new Exception(e[0]);}
    Class<?> cls(String n)throws Exception{return getTargetContext().getClassLoader().loadClass(n);}
    Object obj(String n)throws Exception{return cls(n).getField("INSTANCE").get(null);}
    Object field(Object o,String n)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(n);}
    Object invoke(Object o,String n,Class<?>[] t,Object...a)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(o,a);}catch(NoSuchMethodException e){c=c.getSuperclass();}}throw new NoSuchMethodException(n);}
    Object call(Object o,String n)throws Exception{return invoke(o,n,new Class<?>[0]);}
    void check(boolean b,String m)throws Exception{if(!b)throw new Exception(m);}
    Object window()throws Exception{return call(field(service,"inputView"),"getKeyboardWindow");}
    Object keyboard()throws Exception{return call(window(),"getActiveKeyboard");}
    void select(String id)throws Exception{
        main(()->{invoke(custom,"setActiveId",new Class<?>[]{String.class},id);invoke(custom,"setMode",new Class<?>[]{String.class},"custom");call(manager,"requestCustomizationRefresh");});
        for(int i=0;i<100;i++){SystemClock.sleep(100);boolean[] ok={false};main(()->{if(field(service,"inputView")!=null&&keyboard()!=null)ok[0]=id.equals(invoke(custom,"resolveId",new Class<?>[]{String.class},call(keyboard(),"getKeyboardId")))&&call(keyboard(),"getImportedLayout")!=null;});if(ok[0])return;}
        throw new Exception("Imported layout failed to attach: "+id+", actual="+call(keyboard(),"getKeyboardId")+", imported="+call(keyboard(),"getImportedLayout"));
    }
    View text(View v,String wanted){if(v instanceof android.widget.TextView && wanted.equals(((android.widget.TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=text(((ViewGroup)v).getChildAt(i),wanted);if(found!=null)return found;}return null;}
    void delete(File f){if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File c:a)delete(c);}f.delete();}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){int status=Activity.RESULT_CANCELED;File skins=new File(getTargetContext().getFilesDir(),"baidu_skins");
        try{
            Activity launched=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            Object appPrefs=call(cls("com.osfans.trime.data.prefs.AppPrefs").getField("Companion").get(null),"defaultInstance");
            prefs=(android.content.SharedPreferences)field(appPrefs,"shared");hadStorage=prefs.contains("profile_data_storage_mode");storage=prefs.getString("profile_data_storage_mode",null);
            skins=new File((File)invoke(obj("com.osfans.trime.core.data.DirectExternalStorage"),"resources",new Class<?>[]{Context.class},getTargetContext()),"baidu_skins");
            if(field(launched,"testInputPanel")==null){SystemClock.sleep(500);launched=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();}
            final Activity activity=launched;
            custom=obj("com.osfans.trime.data.keymap.CustomKeyboards");manager=obj("com.osfans.trime.data.theme.ThemeManager");
            saved=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);mode=(String)call(custom,"getMode");active=(String)call(custom,"getActiveId");
            if(skins.list()!=null)oldFolders.addAll(Arrays.asList(skins.list()));
            main(()->invoke(field(activity,"testInputPanel"),"show",new Class<?>[]{Window.class},activity.getWindow()));
            Field instance=cls("com.osfans.trime.ime.keyboard.KeyboardWindow").getDeclaredField("instance");instance.setAccessible(true);
            for(int i=0;i<70&&service==null;i++){main(()->{Object w=instance.get(null);if(w!=null)service=call(w,"getService");});SystemClock.sleep(150);}check(service!=null,"IME unavailable");
            List<String> ids=new ArrayList<>();
            for(int fixture=1;fixture<=2;fixture++){
                Object imported=invoke(obj("com.osfans.trime.data.baiduskin.BdsImporter"),"import",new Class<?>[]{Context.class,File.class,String.class},getTargetContext(),new File(getTargetContext().getCacheDir(),"bds"+fixture+".bds"),"rose"+fixture+".bds");
                List<?> layouts=(List<?>)call(imported,"getIds");check(layouts.size()==4,"Expected four pages for fixture "+fixture);
                result.putString("fixture"+fixture,"4 pages; warnings="+call(imported,"getWarnings"));
                for(Object id:layouts){
                    Object data=invoke(custom,"get",new Class<?>[]{String.class},id);Object skin=call(data,"getImportedSkin");Map<?,?> variants=(Map<?,?>)call(skin,"getVariants");check(variants.size()==4,"Four variants missing");
                    for(Object layout:variants.values()){
                        List<?> keys=(List<?>)call(layout,"getKeys");check(keys.size()>=12,"Incomplete keyboard");
                        check(new File((String)call(layout,"getPreview")).length()>100,"Preview missing");
                        int animated=0,sounds=0;
                        for(Object k:keys){Object style=call(k,"getStyle");if(!((String)call(style,"getSound")).isEmpty())sounds++;
                            for(Object layer:(List<?>)call(style,"getLayers"))if(!((List<?>)call(layer,"getAnimations")).isEmpty())animated++;
                        }
                        check(animated>0,"Animation tracks missing");check(sounds>0,"Sound references missing");
                    }
                    for(boolean dark:new boolean[]{false,true})for(boolean land:new boolean[]{false,true}){
                        Configuration c=new Configuration(getTargetContext().getResources().getConfiguration());c.uiMode=(c.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);c.orientation=land?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
                        Object layout=invoke(skin,"layout",new Class<?>[]{Context.class},getTargetContext().createConfigurationContext(c));check(layout==variants.get((dark?"dark":"light")+"/"+(land?"land":"port")),"Variant selection wrong");
                    }
                    ids.add((String)id);
                }
            }
            for(int page=0;page<4;page++){
                select(ids.get(page));
                main(()->{
                    Object kb=keyboard();Object layout=call(kb,"getImportedLayout");List<?> keys=(List<?>)call(kb,"getKeys");List<?> originals=(List<?>)call(layout,"getKeys");check(keys.size()==originals.size(),"Keys lost in runtime");
                    int w=(Integer)call(kb,"getMinWidth"),h=(Integer)call(kb,"getHeight");check(w>500&&h>100,"Zero keyboard size");
                    for(Object key:keys){check(call(key,"getCustomBgDrawable").getClass().getSimpleName().equals("BdsKeyDrawable"),"Native key renderer missing");check((Integer)call(key,"getHeight")>0,"Zero key height");}
                    if("py_9".equals(call(call(invoke(custom,"get",new Class<?>[]{String.class},ids.get(1)),"getImportedSkin"),"getPage"))&&ids.get(1).equals(invoke(custom,"resolveId",new Class<?>[]{String.class},call(kb,"getKeyboardId")))){int max=0,min=9999;for(Object key:keys){int kh=(Integer)call(key,"getHeight");max=Math.max(max,kh);min=Math.min(min,kh);}check(max>min*1.7,"Nine-key spanning return flattened");}
                    ViewGroup view=(ViewGroup)call(window(),"getCurrentKeyboardView");check(!view.getClipChildren(),"Animation overflow is clipped");check(view.getChildCount()==keys.size(),"Key view count mismatch");
                    for(int ki=0;ki<keys.size();ki++)if("q".equals(call(keys.get(ki),"getLabel"))){
                        check((Integer)call(call(keys.get(ki),"getClick"),"getCode")==android.view.KeyEvent.KEYCODE_Q,"Q binding incorrect");
                        Object drawable=call(keys.get(ki),"getCustomBgDrawable");invoke(view.getChildAt(ki),"setPressedState",new Class<?>[]{boolean.class},true);check((Boolean)call(drawable,"getAnimating"),"KeyView did not start animation");invoke(view.getChildAt(ki),"setPressedState",new Class<?>[]{boolean.class},false);
                    }
                });
            }
            Object data=invoke(custom,"get",new Class<?>[]{String.class},ids.get(0));for(String tone:new String[]{"light","dark"}) { Object layout=((Map<?,?>)call(call(data,"getImportedSkin"),"getVariants")).get(tone+"/port");
            java.nio.file.Files.copy(new File((String)call(layout,"getPreview")).toPath(),new File(getTargetContext().getCacheDir(),"bds-"+tone+"-preview.png").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            int w=(Integer)call(layout,"getWidth"),h=(Integer)call(layout,"getHeight");
            List<?> placed=(List<?>)call(layout,"getKeys");Object selected=placed.get(0);for(Object k:placed)if("q".equals(call(k,"getLabel")))selected=k;
            Object box=call(selected,"getBox"),style=call(selected,"getStyle");float x=(Float)call(box,"getX"),y=(Float)call(box,"getY"),kw=(Float)call(box,"getWidth"),kh=(Float)call(box,"getHeight");
            Bitmap normal=BitmapFactory.decodeFile((String)call(layout,"getBackground"));Bitmap pressed=normal.copy(Bitmap.Config.ARGB_8888,true);Canvas canvas=new Canvas(pressed);
            for(Object k:placed){Object b=call(k,"getBox");float bx=(Float)call(b,"getX"),by=(Float)call(b,"getY"),bw=(Float)call(b,"getWidth"),bh=(Float)call(b,"getHeight");invoke(obj("com.osfans.trime.data.baiduskin.SkinPainter"),"paint",new Class<?>[]{Canvas.class,cls("com.osfans.trime.data.baiduskin.ImportedKeyStyle"),RectF.class,boolean.class,long.class,float.class},canvas,call(k,"getStyle"),new RectF(bx,by,bx+bw,by+bh),k==selected,40L,1f);}
            Bitmap base=BitmapFactory.decodeFile((String)call(layout,"getPreview"));check(!base.sameAs(pressed),"Pressed animation unchanged");
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"bds-"+tone+"-pressed.png"))){pressed.compress(Bitmap.CompressFormat.PNG,100,out);}normal.recycle();pressed.recycle();base.recycle(); }
            String exported=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);check(exported.contains("\"importedSkin\""),"Export lost metadata");invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},exported,true);check(call(invoke(custom,"get",new Class<?>[]{String.class},ids.get(0)),"getImportedSkin")!=null,"Roundtrip lost metadata");
            Activity editor=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.CustomKeyboardActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            main(()->{
                Object registry=call(editor,"getActivityResultRegistry");Map<?,?> codes=(Map<?,?>)field(registry,"keyToRc");Object launcher=field(editor,"baiduLauncher");String launcherKey=null;
                for(Field f:launcher.getClass().getDeclaredFields()){f.setAccessible(true);Object value=f.get(launcher);if(value instanceof String && ((String)value).startsWith("activity_rq#"))launcherKey=(String)value;}
                Integer code=(Integer)codes.get(launcherKey);check(code!=null,"Baidu document launcher missing: "+launcher.getClass()+"/"+codes.keySet());
                android.net.Uri uri=(android.net.Uri)cls("androidx.core.content.FileProvider").getMethod("getUriForFile",Context.class,String.class,File.class).invoke(null,getTargetContext(),getTargetContext().getPackageName()+".fileprovider",new File(getTargetContext().getCacheDir(),"bds1.bds"));
                ((List<String>)field(registry,"launchedKeys")).add(launcherKey);
                invoke(registry,"dispatchResult",new Class<?>[]{int.class,int.class,Intent.class},code,Activity.RESULT_OK,new Intent().setData(uri));
            });
            View[] use={null};for(int i=0;i<900&&use[0]==null;i++){SystemClock.sleep(100);main(()->{for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){View found=text(root,"立即使用");if(found!=null)use[0]=found;}});}
            if(use[0]==null){StringBuilder details=new StringBuilder();main(()->{for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){View message=root.findViewById(android.R.id.message);if(message instanceof android.widget.TextView)details.append(((android.widget.TextView)message).getText());}});throw new Exception("Document import did not show success dialog: "+details);}
            main(()->use[0].performClick());waitForIdleSync();
            check("custom".equals(call(custom,"getMode")),"Use-now failed to select custom mode");check(!ids.contains(call(custom,"getActiveId")),"Use-now failed to select new imported layout");main(()->editor.finish());
            result.putString("document_import","FileProvider document result, conversion progress, success dialog, use-now mode switch");
            exported=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);
            File bad=new File(getTargetContext().getCacheDir(),"bds-invalid.bds");try(FileOutputStream out=new FileOutputStream(bad)){out.write(new byte[32]);}boolean rejected=false;try{invoke(obj("com.osfans.trime.data.baiduskin.BdsImporter"),"import",new Class<?>[]{Context.class,File.class,String.class},getTargetContext(),bad,"invalid.bds");}catch(Exception e){rejected=true;}bad.delete();check(rejected,"Invalid skin accepted");check(exported.equals(invoke(custom,"exportJson",new Class<?>[]{long.class},0L)),"Invalid import changed layouts");
            result.putString("geometry","4 runtime pages, tall nine-key return, unclipped key views");result.putString("render","normal/pressed differ; fixture-only previews saved; 4 variants per page; metadata roundtrip");status=Activity.RESULT_OK;result.putString("result","PASS");
        }catch(Throwable e){result.putString("result","FAIL: "+android.util.Log.getStackTraceString(e));}
        finally{try{if(saved!=null)main(()->{invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},saved,true);invoke(custom,"setMode",new Class<?>[]{String.class},mode);invoke(custom,"setActiveId",new Class<?>[]{String.class},active);call(custom,"flush");call(manager,"requestCustomizationRefresh");});SystemClock.sleep(700);if(prefs!=null)main(()->{android.content.SharedPreferences.Editor e=prefs.edit();if(hadStorage)e.putString("profile_data_storage_mode",storage);else e.remove("profile_data_storage_mode");e.commit();});if(skins.listFiles()!=null)for(File f:skins.listFiles())if(!oldFolders.contains(f.getName()))delete(f);}catch(Throwable e){status=Activity.RESULT_CANCELED;result.putString("restore_error",android.util.Log.getStackTraceString(e));}}
        finish(status,result);
    }
}
