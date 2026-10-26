package dev.codex.trime.smoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

@android.annotation.TargetApi(29)
public final class ModeRunner extends Instrumentation {
    private final Bundle results = new Bundle();
    private Object custom, bindings, toolbar, manager, service;
    private String savedCustom, savedBindings, savedMode, savedActive;
    private List<?> savedToolbar;
    private boolean hadToolbar;
    private File image;
    private android.content.SharedPreferences fontPrefs;
    private Map<String,?> savedFonts;
    private Map<String,String> themeHashes;
    private final String id = "custom_codex_isolation_a";
    private final String id2 = "custom_codex_isolation_b";
    private final String id3 = "custom_codex_isolation_empty";
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        Throwable[] err={null}; runOnMainSync(() -> { try {action.run();}catch(Throwable e){err[0]=e;} });
        if(err[0]!=null) throw new Exception(err[0]);
    }
    private void check(boolean p,String message) throws Exception {if(!p)throw new Exception(message);}
    private Class<?> cls(String n)throws Exception{return getTargetContext().getClassLoader().loadClass(n);}
    private Object singleton(String n)throws Exception{return cls(n).getField("INSTANCE").get(null);}
    private Object field(Object o,String name)throws Exception{
        Class<?> c=o.getClass(); while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();}}
        throw new NoSuchFieldException(name);
    }
    private Object invoke(Object o,String n,Class<?>[] types,Object...args)throws Exception{
        Class<?> c=o.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(NoSuchMethodException e){c=c.getSuperclass();}}
        throw new NoSuchMethodException(n);
    }
    private Object call(Object o,String n)throws Exception{return invoke(o,n,new Class<?>[0]);}
    private Object keyboardWindow()throws Exception{return call(field(service,"inputView"),"getKeyboardWindow");}
    private Object keyboard()throws Exception{return call(keyboardWindow(),"getActiveKeyboard");}
    private Object theme()throws Exception{return call(manager,"getActiveTheme");}
    private void mode(String name)throws Exception{
        main(()->{invoke(custom,"setMode",new Class<?>[]{String.class},name);call(manager,"requestCustomizationRefresh");});
        boolean[] ready={false};
        for(int i=0;i<80&&!ready[0];i++){
            SystemClock.sleep(100);
            main(()->{
                Object iv=field(service,"inputView");
                if(iv==null)return;
                Object rendered=call(iv,"getTheme");
                ready[0]=((Boolean)call(rendered,"isCustom"))==name.equals("custom") && ((Boolean)call(theme(),"isCustom"))==name.equals("custom") && keyboard()!=null;
            });
        }
        check(ready[0],"Mode did not finish rebuilding: "+name);
        waitForIdleSync();
    }
    private void switchKeyboard(String target)throws Exception{
        main(()->invoke(keyboardWindow(),"switchKeyboard",new Class<?>[]{String.class},target));
        SystemClock.sleep(250);waitForIdleSync();
    }
    private Object entry(String action,String icon)throws Exception{
        return cls("com.osfans.trime.data.keymap.ToolbarOverrides$Entry").getConstructor(String.class,boolean.class,String.class).newInstance(action,true,icon);
    }
    private Object customData()throws Exception{return invoke(custom,"get",new Class<?>[]{String.class},id);}
    private Object customKey(int index)throws Exception{
        Object row=((List<?>)call(customData(),"getRows")).get(0);
        return ((List<?>)call(row,"getKeys")).get(index);
    }
    private Object uiToolbar()throws Exception{
        Object bar=call(field(service,"inputView"),"getInputBar");
        return call(call(bar,"getAlwaysUi"),"getButtonsUi");
    }
    private void checkToolbar(String first,int rest)throws Exception{
        Object buttons=uiToolbar();Object firstView=call(buttons,"getFirstButton");
        if(first==null)check(firstView==null,"Empty custom toolbar inherited a first button");
        else check(first.equals(call(field(firstView,"config"),"getAction")),"Toolbar first button was not refreshed: actual="+call(field(firstView,"config"),"getAction")+",keyboard="+call(keyboard(),"getKeyboardId"));
        check(((ViewGroup)call(buttons,"getRoot")).getChildCount()==rest,"Unexpected toolbar buttons appended");
    }
    private void hashes(File file,Map<String,String> output)throws Exception{
        if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)hashes(child,output);}
        else if(file.getName().endsWith(".trime.yaml")||file.getName().equals("trime.yaml")){
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            output.put(file.getAbsolutePath(),Arrays.toString(hash));
        }
    }
    private Map<String,String> themeHashes()throws Exception{
        Map<String,String> output=new TreeMap<>();Object dm=singleton("com.osfans.trime.core.data.DataManager");
        hashes((File)call(dm,"getUserDataDir"),output);hashes((File)call(dm,"getSharedDataDir"),output);return output;
    }
    private String fixture(){
        String path=image.getAbsolutePath();
        return "{\"format\":\"trime-custom-keyboards\",\"formatVersion\":1,\"keyboards\":{"+
          "\""+id+"\":{\"name\":\"isolation-test\",\"rowSpacing\":16,\"toolbarBackground\":{\"type\":\"IMAGE\",\"image\":\""+path+"\"},\"background\":{\"type\":\"IMAGE\",\"color\":\"#FFFF0000\",\"image\":\""+path+"\"},\"toolbar\":[{\"action\":\"paste\",\"icon\":\"CUSTOM_ONE\"}],\"rows\":[{\"height\":40,\"keys\":["+
          "{\"label\":\"A\",\"width\":34,\"bindings\":{\"click\":\"a\",\"longClick\":\"b\"},\"background\":{\"type\":\"COLOR\",\"color\":\"#112233\",\"image\":\"ignored.png\"}},"+
          "{\"label\":\"B\",\"width\":33,\"bindings\":{\"click\":\"b\"},\"background\":{\"type\":\"TRANSPARENT\",\"color\":\"#FFFFFFFF\",\"image\":\"ignored.png\"}},"+
          "{\"label\":\"C\",\"width\":33,\"bindings\":{\"click\":\"c\"},\"background\":{\"type\":\"IMAGE\",\"color\":\"#FFFFFFFF\",\"image\":\""+path+"\"}}]},{\"height\":80,\"keys\":[{\"label\":\"display only\",\"width\":100}]}]},"+
          "\""+id2+"\":{\"name\":\"second\",\"rowSpacing\":0,\"toolbar\":[{\"action\":\"copy\"},{\"action\":\"Mode_switch\"}],\"rows\":[{\"keys\":[{\"label\":\"Space\",\"width\":100,\"bindings\":{\"click\":\"space\"}}]}]},"+
          "\""+id3+"\":{\"name\":\"empty-toolbar\",\"toolbar\":[],\"rows\":[{\"keys\":[{\"label\":\"Space\",\"width\":100,\"bindings\":{\"click\":\"space\",\"longClick\":\"\"}}]}]}}}";
    }
    private android.widget.EditText findInput(View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){android.widget.EditText result=findInput(group.getChildAt(i));if(result!=null)return result;}
        }
        return null;
    }
    private View focusedDialog()throws Exception{
        for(View v:WindowInspector.getGlobalWindowViews())if(v.hasWindowFocus())return v;
        throw new Exception("No focused app dialog");
    }
    private View findText(View view,String text){
        if(view instanceof TextView && text.equals(((TextView)view).getText().toString()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private ListView list(View view){
        if(view instanceof ListView)return (ListView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ListView found=list(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private View recycler(View view) throws Exception {
        if (cls("androidx.recyclerview.widget.RecyclerView").isInstance(view)) return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=recycler(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    @Override public void onStart(){
        int result=Activity.RESULT_CANCELED;
        try{
            Activity activity=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            main(()->{
                View list=recycler(activity.getWindow().getDecorView());
                check(list!=null,"Settings list missing");
                check((Integer)call(list,"getItemDecorationCount")>0,"Settings cards missing");
                check(list.getPaddingTop()==0,"Extra whitespace above settings list");
                Object adapter=call(list,"getAdapter");
                int categories=0;
                int count=(Integer)call(adapter,"getItemCount");
                int sectionLayout=getTargetContext().getResources().getIdentifier("preference_category_section","layout",getTargetContext().getPackageName());
                for(int n=0;n<count;n++){
                    Object item=invoke(adapter,"getItem",new Class<?>[]{int.class},n);
                    if(cls("androidx.preference.PreferenceCategory").isInstance(item)){
                        check((Integer)call(item,"getLayoutResource")==sectionLayout,"Category title layout mismatch");
                        check(!(Boolean)call(item,"isIconSpaceReserved"),"Category title reserves an icon gap");categories++;
                    }
                }
                check(categories==3,"Expected three aligned settings sections");
                check(list.getPaddingLeft()>=15*activity.getResources().getDisplayMetrics().density,"Settings spacing missing");
                android.util.TypedValue accent=new android.util.TypedValue();
                activity.getTheme().resolveAttribute(android.R.attr.colorAccent,accent,true);
                int expected=activity.getColor(activity.getResources().getIdentifier("m3_primary","color",getTargetContext().getPackageName()));
                check(accent.data==expected,"Settings accent differs from palette");
                results.putString("settings_ui","card decoration, spacing and rose accent applied");
            });
            fontPrefs=getTargetContext().getSharedPreferences("font_overrides",0);
            savedFonts=new HashMap<>(fontPrefs.getAll());
            custom=singleton("com.osfans.trime.data.keymap.CustomKeyboards");
            bindings=singleton("com.osfans.trime.data.keymap.KeyBindingOverrides");
            toolbar=singleton("com.osfans.trime.data.keymap.ToolbarOverrides");
            manager=singleton("com.osfans.trime.data.theme.ThemeManager");
            savedCustom=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);
            savedBindings=(String)invoke(bindings,"exportJson",new Class<?>[]{long.class},0L);
            savedMode=(String)call(custom,"getMode");savedActive=(String)call(custom,"getActiveId");
            savedToolbar=new ArrayList<>((List<?>)call(toolbar,"entries"));hadToolbar=(Boolean)call(toolbar,"hasCustomization");
            themeHashes=themeHashes();
            image=new File(getTargetContext().getCacheDir(),"mode-test.png");
            Bitmap bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.TRANSPARENT);bitmap.setPixel(0,0,Color.GREEN);
            try(FileOutputStream out=new FileOutputStream(image)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
            main(()->{Object panel=field(activity,"testInputPanel");invoke(panel,"show",new Class<?>[]{android.view.Window.class},activity.getWindow());});
            Class<?> wc=cls("com.osfans.trime.ime.keyboard.KeyboardWindow");Field wf=wc.getDeclaredField("instance");wf.setAccessible(true);
            for(int i=0;i<70&&service==null;i++){main(()->{Object w=wf.get(null);if(w!=null)service=call(w,"getService");});if(service==null)SystemClock.sleep(200);}
            check(service!=null,"IME unavailable");
            final android.widget.EditText[] ownInput={null};
            main(()->{ownInput[0]=(android.widget.EditText)field(field(activity,"testInputPanel"),"input");});
            // Speech lifecycle now uses the built-in controller; the dedicated Mie14Runner
            // checks callback delivery and cancellation without accessing the microphone.
            results.putString("voice_client","built-in speech; dedicated Mie14Runner regression");
            main(()->{
                invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},fixture(),false);
                invoke(custom,"setActiveId",new Class<?>[]{String.class},id);
                invoke(bindings,"setBinding",new Class<?>[]{String.class,int.class,String.class,String.class},"default",0,"click","z");
                invoke(toolbar,"setEntries",new Class<?>[]{List.class},Arrays.asList(entry("paste","THEME_ONLY")));
            });
            mode("custom");
            main(()->{
                check((Boolean)call(theme(),"isCustom"),"Custom theme marker missing");
                check(!((Map<?,?>)call(theme(),"getPresetKeys")).containsKey("a"),"Custom actions inherited YAML aliases");
                List<?> keys=(List<?>)call(keyboard(),"getKeys");check(keys.size()==4,"Custom layout key count wrong");
                int h1 = (Integer)call(keys.get(0), "getHeight");
                int h2 = (Integer)call(keys.get(3), "getHeight");
                check(Math.abs(h2 - 2 * h1) < 24, "Runtime row heights ignored 40:80 ratio: " + h1 + ":" + h2);
                int totalHeight = (Integer)call(keyboard(), "getKeyboardHeight");
                check(Math.abs(totalHeight - 120 * getTargetContext().getResources().getDisplayMetrics().density) < 2, "Absolute row height sum ignored");
                results.putString("row_height", "40/80dp rows rendered proportionally, total120dp");
                ViewGroup actual=(ViewGroup)call(keyboardWindow(),"getCurrentKeyboardView");
                float density=getTargetContext().getResources().getDisplayMetrics().density;
                View firstKey=actual.getChildAt(0);
                check(firstKey.getPaddingTop()+firstKey.getPaddingBottom()==(int)(16*density),"16dp row spacing missing from runtime key");
                Object config=((Map<?,?>)call(theme(),"getPresetKeyboards")).get("default");
                Object scaled=cls("com.osfans.trime.ime.keyboard.Keyboard").getConstructor(android.content.Context.class,
                    cls("com.osfans.trime.data.theme.Theme"),int.class,cls("com.osfans.trime.data.theme.model.TextKeyboard"),String.class,int.class)
                    .newInstance(getTargetContext(),theme(),900,config,"default",totalHeight/2);
                Method getter=null;for(Method method:scaled.getClass().getDeclaredMethods())if(method.getName().startsWith("getVerticalGap")){getter=method;break;}
                check(getter!=null,"Spacing getter missing");getter.setAccessible(true);
                check((Integer)getter.invoke(scaled)==(int)((int)(16*density)*(totalHeight/2f)/totalHeight),"Floating resize did not scale row spacing");
                call(keyboard(),"recordHeatLayout");
                results.putString("row_spacing_runtime","16dp applied to key padding, scales with floating height; row heights unchanged");
                call(keys.get(3),"onPressed");call(keys.get(3),"onReleased");
                check(!(Boolean)call(keys.get(3),"isPressed"),"Display-only key stayed pressed");
                Object click=Enum.valueOf((Class)cls("com.osfans.trime.ime.keyboard.KeyBehavior"),"CLICK");
                int code=(Integer)invoke(keys.get(0),"getCode",new Class<?>[]{click.getClass()},click);
                check(code==KeyEvent.KEYCODE_A,"Theme binding overrode custom key A");
                invoke(keys.get(0),"setOn",new Class<?>[]{boolean.class},true);
                check((Integer)call(keys.get(0),"getTextColor")==Color.WHITE,"Sticky custom key lost label contrast");
                invoke(keys.get(0),"setOn",new Class<?>[]{boolean.class},false);
                check(((ColorDrawable)call(keys.get(0),"getCustomBgDrawable")).getColor()==Color.rgb(17,34,51),"Solid key background wrong");
                check(((ColorDrawable)call(keys.get(1),"getCustomBgDrawable")).getColor()==Color.TRANSPARENT,"Transparent key fell back to theme");
                check(call(keys.get(2),"getCustomBgDrawable").getClass().getSimpleName().equals("AssetBitmapDrawable"),"Image key background missing");
                check(((String)call(call(customData(),"resolvedBackground"),"getColor")).isEmpty(),"Image keyboard retained a color source");
                View kv=(View)call(keyboardWindow(),"getCurrentKeyboardView");
                check(((View)kv.getParent()).getBackground().getClass().getSimpleName().equals("AssetBitmapDrawable"),"Keyboard image background missing");
                Object bar=call(field(service,"inputView"),"getInputBar");
                check(((View)call(bar,"getView")).getBackground().getClass().getSimpleName().equals("AssetBitmapDrawable"),"Custom toolbar background not applied");
                results.putString("toolbar_background","independent image background rendered");
                checkToolbar("paste",0);
                Object first=call(uiToolbar(),"getFirstButton");
                check("CUSTOM_ONE".equals(call(call(field(first,"config"),"getForeground"),"getStyle")),"Theme icon leaked into custom toolbar");
                results.putString("custom_mode","independent bindings; color/transparent/image rendered; dedicated toolbar");
            });
            Activity stats=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.InputStatsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->{
                Object snapshot=field(stats,"lastSnapshot");
                for(String period:Arrays.asList("Daily","Weekly","Monthly","Yearly")){
                    try{((Map<?,?>)call(call(snapshot,"get"+period),"getKeyCounts")).clear();}catch(NoSuchMethodException ignored){}
                }
                invoke(stats,"renderHeat",new Class<?>[0]);
                int heatId=getTargetContext().getResources().getIdentifier("stats_heat_grid","id",getTargetContext().getPackageName());
                ViewGroup grid=stats.findViewById(heatId);
                check(grid.getChildCount()==2,"Heatmap did not keep both rows");
                int cells=0;
                for(int row=0;row<grid.getChildCount();row++){
                    ViewGroup line=(ViewGroup)grid.getChildAt(row);
                    for(int n=0;n<line.getChildCount();n++)if(line.getChildAt(n) instanceof TextView){
                        check(((TextView)line.getChildAt(n)).getText().toString().endsWith("0%"),"Untouched key missing 0%");cells++;
                    }
                }
                check(cells==4,"Heatmap omitted a visible key");
                stats.finish();
                results.putString("heatmap","all four fixture keys and two rows shown at 0%, including display-only key");
            });
            // Finish/resume triggers an asynchronous IME reattach; wait for the editor before switching.
            waitForIdleSync();SystemClock.sleep(600);waitForIdleSync();
            switchKeyboard(id2);main(()->{
                checkToolbar("copy",1);
                View zero=((ViewGroup)call(keyboardWindow(),"getCurrentKeyboardView")).getChildAt(0);
                check(zero.getPaddingTop()+zero.getPaddingBottom()==0,"Zero spacing fell back to theme gap");
                View key=((ViewGroup)call(keyboardWindow(),"getCurrentKeyboardView")).getChildAt(0);
                if((Boolean)call(singleton("com.osfans.trime.ime.voice.VoiceSettings"),"getEnabled")) {
                    check((Boolean)call(key,"voiceLongPress"),"Space did not default to voice hold");
                    String ownBefore=ownInput[0].getText().toString();
                    key.performLongClick();
                    check((Boolean)field(key,"voiceHeld"),"Long press did not enter voice hold");
                    Object release=call(key,"getOnRelease");
                    Object behavior=Enum.valueOf((Class)cls("com.osfans.trime.ime.keyboard.KeyBehavior"),"LONG_CLICK");
                    invoke(release,"invoke",new Class<?>[]{Object.class,Object.class},behavior,true);
                    check(!(Boolean)field(key,"voiceHeld"),"Release did not end voice hold");
                    check(ownBefore.equals(ownInput[0].getText().toString()),"Voice release inserted a space");
                }
                check((Integer)call(invoke(theme(),"resolveAction",new Class<?>[]{String.class},"BibiVoice"),"getCode")==KeyEvent.KEYCODE_VOICE_ASSIST,"Voice picker action code wrong");
                results.putString("voice_binding","space default, long-press/release and dedicated action checked without recording");
            });
            switchKeyboard(id3);main(()->{
                checkToolbar(null,0);
                View key=((ViewGroup)call(keyboardWindow(),"getCurrentKeyboardView")).getChildAt(0);
                check(!(Boolean)call(key,"voiceLongPress"),"Explicit unbind still starts voice");
            });
            results.putString("custom_toolbar_switch","first button refreshed; empty remains empty");
            mode("theme");switchKeyboard("default");
            main(()->{
                check(!(Boolean)call(theme(),"isCustom"),"Theme mode still custom");
                Object first=((List<?>)call(keyboard(),"getKeys")).get(0);
                Object click=Enum.valueOf((Class)cls("com.osfans.trime.ime.keyboard.KeyBehavior"),"CLICK");
                check((Integer)invoke(first,"getCode",new Class<?>[]{click.getClass()},click)==KeyEvent.KEYCODE_Z,"Theme binding was lost on mode switch");
                check(call(first,"getCustomBgDrawable")==null,"Custom skin leaked into theme mode");
                checkToolbar("paste",0);
                Object button=call(uiToolbar(),"getFirstButton");
                check("THEME_ONLY".equals(call(call(field(button,"config"),"getForeground"),"getStyle")),"Theme toolbar configuration was lost");
                results.putString("theme_mode","theme bindings and toolbar preserved; no custom skin");
            });
            main(()->invoke(custom,"setActiveId",new Class<?>[]{String.class},id));mode("custom");
            main(()->checkToolbar("paste",0));
            Activity editor=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.CustomKeyboardEditActivity").putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->{
                int previewId=getTargetContext().getResources().getIdentifier("cbe_preview","id",getTargetContext().getPackageName());
                ViewGroup preview=editor.findViewById(previewId);
                Drawable skin=((ViewGroup)preview.getChildAt(0)).getChildAt(0).getBackground();
                check(skin instanceof android.graphics.drawable.LayerDrawable,"Editor key lost pink outline layer");
                results.putString("editor_border","key skin preserved beneath separate outline layer");
            });
            main(()->invoke(editor,"showRowSpacingEditor",new Class<?>[0]));
            waitForIdleSync();SystemClock.sleep(200);
            main(()->{
                View dialog=focusedDialog();android.widget.EditText input=findInput(dialog);
                check(input!=null,"Row spacing input missing");input.setText("99");
                dialog.findViewById(android.R.id.button1).performClick();
                check((Integer)call(customData(),"getRowSpacing")==16,"Invalid row spacing accepted");
                input.setText("0");dialog.findViewById(android.R.id.button1).performClick();
                check((Integer)call(customData(),"getRowSpacing")==0,"Zero spacing did not save");
                ViewGroup preview=editor.findViewById(getTargetContext().getResources().getIdentifier("cbe_preview","id",getTargetContext().getPackageName()));
                android.widget.LinearLayout.LayoutParams params=(android.widget.LinearLayout.LayoutParams)((ViewGroup)preview.getChildAt(0)).getChildAt(0).getLayoutParams();
                check(params.topMargin+params.bottomMargin==0,"Zero spacing preview retained margins");
            });
            main(()->invoke(editor,"showRowSpacingEditor",new Class<?>[0]));
            waitForIdleSync();SystemClock.sleep(200);
            main(()->{
                View dialog=focusedDialog();findInput(dialog).setText("12");dialog.findViewById(android.R.id.button1).performClick();
                check((Integer)call(customData(),"getRowSpacing")==12,"12dp spacing did not save");
                ViewGroup preview=editor.findViewById(getTargetContext().getResources().getIdentifier("cbe_preview","id",getTargetContext().getPackageName()));
                android.widget.LinearLayout.LayoutParams params=(android.widget.LinearLayout.LayoutParams)((ViewGroup)preview.getChildAt(0)).getChildAt(0).getLayoutParams();
                check(params.topMargin+params.bottomMargin>0,"Preview did not update row spacing");
                String backup=(String)invoke(custom,"exportJson",new Class<?>[]{long.class},0L);
                check(backup.contains("\"rowSpacing\":12"),"Export omitted row spacing");
                results.putString("row_spacing_editor","0 and 12dp saved, invalid value rejected, preview and JSON updated");
            });
            main(()->invoke(editor,"showRowHeightEditor",new Class<?>[]{int.class},0));
            waitForIdleSync();SystemClock.sleep(300);
            main(()->{
                View dialog = focusedDialog();
                android.widget.EditText input = findInput(dialog);
                check(input != null, "Row height input missing");
                input.setText("0");
                dialog.findViewById(android.R.id.button1).performClick();
                check((Integer)call(((List<?>)call(customData(),"getRows")).get(0),"getHeight")==40,"Invalid row height was accepted");
                input.setText("72");
                dialog.findViewById(android.R.id.button1).performClick();
                List<?> rows=(List<?>)call(customData(),"getRows");
                check((Integer)call(rows.get(0),"getHeight")==72,"Row height editor did not save");
                results.putString("row_height_editor", "72dp value saved via dialog");
            });
            main(()->invoke(editor,"showKeyEditor",new Class<?>[]{int.class,int.class},0,0));
            waitForIdleSync();SystemClock.sleep(300);
            final View[] dialog={null};
            main(()->{
                dialog[0]=focusedDialog();
                int res=getTargetContext().getResources().getIdentifier("custom_kb_key_bg","string",getTargetContext().getPackageName());
                View bg=findText(dialog[0],getTargetContext().getString(res));check(bg!=null,"Key background editor missing");((View)bg.getParent()).performClick();
            });
            waitForIdleSync();SystemClock.sleep(200);
            main(()->{
                ListView choice=list(focusedDialog());check(choice!=null&&choice.getAdapter().getCount()==3,"Background picker must expose exactly three modes");
                choice.performItemClick(choice.getChildAt(1),1,choice.getAdapter().getItemId(1));
                check("COLOR".equals(call(call(customKey(0),"resolvedBackground"),"getType").toString()),"Unsaved background edited stored data");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);SystemClock.sleep(150);sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);SystemClock.sleep(150);
            main(()->{
                check("COLOR".equals(call(call(customKey(0),"resolvedBackground"),"getType").toString()),"Cancel persisted draft background");
                editor.finish();
                results.putString("editor","three explicit background choices; draft cancel preserves stored background");
            });
            Activity fonts=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.FontSettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->{
                Object fontStore=singleton("com.osfans.trime.data.theme.FontOverrides");
                String selected=(String)invoke(fontStore,"selection",new Class<?>[]{String.class},"key");
                String label=(String)invoke(fontStore,"name",new Class<?>[]{String.class},selected);
                View keyButton=findText(fonts.getWindow().getDecorView(),"键盘按键 · "+label);
                check(keyButton!=null,"Font key setting missing");keyButton.performClick();
            });
            waitForIdleSync();SystemClock.sleep(200);
            main(()->{ListView choices=list(focusedDialog());check(choices!=null,"Font choices missing");choices.performItemClick(choices.getAdapter().getView(3,null,choices),3,3);});
            main(()->{
                Object fontStore=singleton("com.osfans.trime.data.theme.FontOverrides");
                check("system:monospace".equals(invoke(fontStore,"selection",new Class<?>[]{String.class},"key")),"Font choice did not persist");
                Object typeface=call(call(theme(),"getFonts"),"getKey");
                check(typeface.equals(android.graphics.Typeface.MONOSPACE),"Keyboard font override not applied");
                check(((Map<?,?>)call(fontStore,"getRoles")).size()==8,"Expected eight font areas");
                fonts.finish();results.putString("fonts","eight areas; keyboard font selected via UI, persisted and resolved");
            });
            check(themeHashes.equals(themeHashes()),"A YAML theme file changed");
            results.putString("yaml_files","unchanged SHA-256 checksums ("+themeHashes.size()+" files)");
            result=Activity.RESULT_OK;results.putString("result","PASS");
        }catch(Throwable e){results.putString("result","FAIL: "+android.util.Log.getStackTraceString(e));}
        finally{
            try{if(savedCustom!=null)main(()->{
                invoke(custom,"importJson",new Class<?>[]{String.class,boolean.class},savedCustom,true);
                invoke(bindings,"importJson",new Class<?>[]{String.class,boolean.class},savedBindings,true);
                if(hadToolbar)invoke(toolbar,"setEntries",new Class<?>[]{List.class},savedToolbar);else call(toolbar,"resetAll");
                invoke(custom,"setMode",new Class<?>[]{String.class},savedMode);
                invoke(custom,"setActiveId",new Class<?>[]{String.class},savedActive);
                call(custom,"flush");call(bindings,"flush");call(toolbar,"flush");call(manager,"requestCustomizationRefresh");
            });SystemClock.sleep(700);}catch(Throwable e){result=Activity.RESULT_CANCELED;results.putString("restore_error",android.util.Log.getStackTraceString(e));}
            if(savedFonts!=null){android.content.SharedPreferences.Editor edit=fontPrefs.edit().clear();
                for(Map.Entry<String,?> item:savedFonts.entrySet()){
                    if(item.getValue() instanceof String)edit.putString(item.getKey(),(String)item.getValue());
                    else if(item.getValue() instanceof Long)edit.putLong(item.getKey(),(Long)item.getValue());
                }edit.commit();
                try{main(()->call(manager,"requestCustomizationRefresh"));}catch(Exception ignored){}
            }
            if(image!=null)image.delete();
        }
        finish(result,results);
    }
}
