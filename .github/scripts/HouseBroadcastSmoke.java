package com.housecommander.desktop;

import com.housecommander.core.AssetSource;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.SpectatorCardGroup;
import com.housecommander.spectator.*;
import com.housecommander.token.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Behavior and render gates for the shared broadcast model, with no fabricated game results. */
public final class HouseBroadcastSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        AssetSource assets = path -> Files.newInputStream(root.resolve("forge-gui-android/assets").resolve(path));
        TokenRegistryLoader catalog = TokenRegistryLoader.load(assets);
        long scripts;
        try (java.util.stream.Stream<Path> paths = Files.list(root.resolve("forge-gui/res/tokenscripts"))) {
            scripts = paths.filter(p -> p.toString().endsWith(".txt")).count();
        }
        check(catalog.size() == scripts, "Catalog must include every bundled script");
        check(catalog.definitions().stream().anyMatch(d -> d.power.contains("*")), "Variable P/T preserved");
        TokenArtResolver.install(catalog);
        System.out.println("BROADCAST_CATALOG_PASS " + catalog.size());

        List<LiveGameState.CardState> swarm = new ArrayList<>();
        for (int i = 0; i < 600; i++) swarm.add(card("Squirrel Token", "Creature Squirrel", i, 1, 1, false, 0));
        swarm.add(card("Squirrel Token", "Creature Squirrel", 600, 1, 1, false, 1));
        List<SpectatorCardGroup> groups = SpectatorCardGroup.group(swarm);
        check(groups.size() == 2 && groups.get(0).count() == 600, "Swarm grouping with distinct damage");
        check(groups.get(0).cardIds().size() == 600, "Grouped IDs must preserve combat endpoints");
        List<LiveGameState.CardState> roles = List.of(card("Human Token", "Creature Human Warrior", 700, 1, 1, false, 0),
                card("Human Token", "Creature Human Wizard", 701, 1, 1, false, 0));
        check(SpectatorCardGroup.group(roles).size() == 2, "Different token roles cannot collapse");
        LiveGameState.CardState original=roles.get(0);
        LiveGameState.CardState alternate=new LiveGameState.CardState(original.name(),"","",false,true,false,true,false,
                false,false,1,1,List.of(),702,0,false,original.typeLine(),"green",List.of(),"This token cannot block.");
        check(SpectatorCardGroup.group(List.of(original,alternate)).size()==2,"Different non-keyword rules cannot collapse");
        System.out.println("BROADCAST_SWARM_PASS 601 permanents -> 2 piles");

        LiveGameState live = fixture();
        TournamentTableView table = new TournamentTableView(live);
        check(table.seats().size() == 4, "Four visible players");
        check(table.seats().get(2).x > .5 && table.seats().get(3).x < .5, "Clockwise seating");
        check(table.seats().get(0).active && table.seats().get(1).priority && table.seats().get(1).responding,
                "Active turn and actual responder spotlight");
        check(table.seats().get(3).player.lost(), "Eliminated seat retained");
        check(live.focusState() == FocusState.STACK, "Stack focus precedence");
        List<LiveGameState.PlayerState> mutable = new ArrayList<>(live.players());
        LiveGameState immutable = new LiveGameState(2,"",1,"MAIN","A",mutable,List.of(),false,"");
        mutable.clear(); check(immutable.players().size()==4,"Snapshots must copy mutable collections");
        try { immutable.players().clear(); throw new AssertionError("Snapshot mutable"); }
        catch (UnsupportedOperationException expected) { }
        BroadcastSettings settings = new BroadcastSettings();
        Path prefs = Files.createTempDirectory("house-viewer-smoke").resolve("broadcast.properties");
        settings.focusResponses=false; settings.animate=false;settings.save(prefs.toFile());
        BroadcastSettings loaded=BroadcastSettings.load(prefs.toFile());
        check(!loaded.focusResponses&&!loaded.animate&&loaded.themedTokens,"Preferences round trip");
        check(!table.seats().get(1).spotlight(loaded),"Disable response spotlight");
        Files.delete(prefs);Files.delete(prefs.getParent());
        System.out.println("BROADCAST_STATE_AND_SETTINGS_PASS");

        Path out=root.resolve("forge-gui-desktop/target/broadcast-preview.png");Files.createDirectories(out.getParent());
        SwingUtilities.invokeAndWait(()->{
            DesktopCardArtCache art=new DesktopCardArtCache();
            try{
                DesktopTournamentTable panel=new DesktopTournamentTable(art,new BroadcastSettings(),c->{},p->{});
                panel.setSize(1280,800);panel.update(live);
                BufferedImage image=new BufferedImage(1280,800,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();try{panel.paint(g);}finally{g.dispose();panel.removeNotify();}
                ImageIO.write(image,"png",out.toFile());
                DesktopTournamentTable phone=new DesktopTournamentTable(art,new BroadcastSettings(),c->{},p->{});
                phone.setSize(390,620);phone.update(live);
                BufferedImage small=new BufferedImage(390,620,BufferedImage.TYPE_INT_RGB);
                g=small.createGraphics();try{phone.paint(g);}finally{g.dispose();phone.removeNotify();}
                ImageIO.write(small,"png",out.resolveSibling("broadcast-phone-preview.png").toFile());
                phone.setSize(780,320);
                BufferedImage landscape=new BufferedImage(780,320,BufferedImage.TYPE_INT_RGB);
                g=landscape.createGraphics();try{phone.paint(g);}finally{g.dispose();phone.removeNotify();}
                ImageIO.write(landscape,"png",out.resolveSibling("broadcast-landscape-preview.png").toFile());
                Set<Integer> fingerprints=new HashSet<>();
                for(LiveGameState.CardState c:roles){
                    javax.swing.ImageIcon icon=art.zoomIcon(c,200,280,null);
                    check(icon!=null,"Offline art required");
                    BufferedImage token=new BufferedImage(200,280,BufferedImage.TYPE_INT_ARGB);
                    Graphics2D tg=token.createGraphics();tg.drawImage(icon.getImage(),0,0,null);tg.dispose();
                    fingerprints.add(Arrays.hashCode(token.getRGB(0,0,200,280,null,0,200)));
                }
                check(fingerprints.size()==2,"Warrior and Wizard illustrations must differ");
                for(TokenDefinition d:catalog.definitions()){
                    LiveGameState.CardState c=new LiveGameState.CardState(d.name,"t:"+d.tokenId,"",false,true,false,
                            d.types.contains("Creature"),false,false,false,1,1,List.of(),1,0,false,
                            String.join(" ",d.types)+" "+String.join(" ",d.subtypes),String.join(" ",d.colors),d.keywords);
                    check(catalog.resolve(c)==d,"Exact script registry resolution");
                    check(art.cardIcon(c,40,56,null)!=null,"Every catalog token must render offline");
                }
            }catch(Exception error){throw new RuntimeException(error);}finally{art.shutdown();}
        });
        System.out.println("BROADCAST_RENDER_PASS desktop + phone + every catalog token");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static LiveGameState.CardState card(String name,String type,int id,int power,int toughness,boolean attack,int damage){
        return new LiveGameState.CardState(name,"","",false,true,false,type.contains("Creature"),false,
                attack,false,power,toughness,List.of(),id,damage,false,type,"green",List.of());
    }
    private static LiveGameState.PlayerState player(String name,int life,boolean lost,List<LiveGameState.CardState> cards){
        return new LiveGameState.PlayerState(name,life,0,5,72,lost,cards,List.of(name+" commander"),
                List.of(name+" commander"),List.of("Forest","Cultivate"),List.of(),List.of("Cloud: 4/21"));
    }
    private static LiveGameState fixture(){
        List<LiveGameState.CardState> troops=new ArrayList<>();
        troops.add(card("Human Warrior Token","Creature Human Warrior",1,2,2,true,0));
        troops.add(card("Dragon Token","Creature Dragon",2,5,5,true,1));
        for(int i=0;i<40;i++)troops.add(card("Human Warrior Token","Creature Human Warrior",20+i,2,2,false,0));
        List<LiveGameState.PlayerState> players=List.of(
                player("Cloud, Ex-SOLDIER",37,false,troops),
                player("Katara the Fearless",32,false,List.of(card("Spirit Token","Creature Spirit",3,1,1,false,0),
                        card("Human Wizard Token","Creature Human Wizard",4,1,1,false,0),card("Treasure Token","Artifact Treasure",5,0,0,false,0))),
                player("Ulalek, Fused Atrocity",25,false,List.of(card("Eldrazi Spawn Token","Creature Eldrazi Spawn",6,0,1,false,0),
                        card("Squirrel Token","Creature Squirrel",7,1,1,false,0),card("Clue Token","Artifact Clue",8,0,0,false,0))),
                player("Urza, Chief Artificer",0,true,List.of(card("Construct Token","Artifact Creature Construct",9,6,6,false,0))));
        return new LiveGameState(100,"GameEventSpellAbilityCast",8,"COMBAT_DECLARE_BLOCKERS",players.get(0).name(),players,
                List.of("Katara responds: remove attacking Dragon", "Cloud's combat trigger"),false,"",players.get(1).name(),players.get(1).name(),
                List.of(new LiveGameState.TargetLink(1,-1,players.get(1).name(),"Attacks",LiveGameState.TargetLink.Kind.ATTACK),
                        new LiveGameState.TargetLink(999,2,"","Removal",LiveGameState.TargetLink.Kind.TARGET)));
    }
}
