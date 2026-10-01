package com.housecommander.forgebridge;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/** Forge's platform boundary for local AI games without a LibGDX window. */
public final class HouseHeadlessGui implements IGuiBase {
    private final String assetsDir;
    private final String version;

    public HouseHeadlessGui(File root, String version) {
        this.assetsDir = root.getAbsolutePath() + File.separator;
        this.version = version;
    }

    @Override public boolean isRunningOnDesktop() { return false; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return version; }
    @Override public void invokeInEdtNow(Runnable task) { task.run(); }
    @Override public void invokeInEdtLater(Runnable task) { task.run(); }
    @Override public void invokeInEdtAndWait(Runnable task) { task.run(); }
    @Override public void runBackgroundTask(String message, Runnable task) { task.run(); }
    @Override public boolean isGuiThread() { return false; }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(FSkinProp prop) { return null; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlay, float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String text, boolean reminder) { return text; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }
    @Override public void showCardList(String title, String message, List<PaperCard> cards) { }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> cards) { return false; }
    @Override public void showBugReportDialog(String title, String text, boolean exit) { throw new IllegalStateException(title + ": " + text); }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { }
    @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) { return defaultOption; }
    @Override public String showInputDialog(String message, String title, FSkinProp icon, String initial, List<String> options, boolean numeric) { return initial; }
    @Override public String showFileDialog(String title, String directory) { return directory; }
    @Override public File getSaveFile(File file) { return file; }
    @Override public <T> List<T> order(String title, String top, int min, int max, List<T> source, List<T> destination) { return destination; }
    @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) { return selected == null ? new ArrayList<>() : new ArrayList<>(selected); }
    @Override public PaperCard chooseCard(String title, String message, List<PaperCard> cards) { return cards.isEmpty() ? null : cards.get(0); }
    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean synchronizedPlayback) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return null; }
    @Override public HostedMatch hostMatch() { return null; }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
