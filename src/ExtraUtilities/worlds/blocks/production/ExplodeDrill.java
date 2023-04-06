package ExtraUtilities.worlds.blocks.production;

import ExtraUtilities.ExtraUtilitiesMod;
import ExtraUtilities.content.EUFx;
import ExtraUtilities.content.EUGet;
import ExtraUtilities.worlds.drawer.DrawFunc;
import ExtraUtilities.worlds.meta.EUStatValues;
import arc.Core;
import arc.audio.Sound;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.ObjectFloatMap;
import arc.struct.Seq;
import arc.util.Eachable;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.*;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Liquids;
import mindustry.core.World;
import mindustry.entities.Effect;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Sounds;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.graphics.Shaders;
import mindustry.input.InputHandler;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.consumers.ConsumeLiquidBase;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * 超级缝合怪！
 * 说实话这个不难理解甚至不难写
 * */

public class ExplodeDrill extends Drill {
    public float drillTimeBurst = 60f * 4.5f;
    public Interp speedCurve = Interp.pow2In;
    public float shake = 2f;
    public float invertedTime = 200f;
    public int reRange = 4;
    public float reFindTimer = 1f * 60;
    public int digAmount = 5;

    public Color glowColor = Color.valueOf("bf92f9");

    public @Nullable ConsumeLiquidBase coolant;
    public float coolantMultiplier = 1f;

    public ObjectFloatMap<Item> drillMultipliers = new ObjectFloatMap<>();

    public TextureRegion bottom, top;
    public TextureRegion[] plasmaRegions;
    public int plasmas = 4;
    public float circleRange = 0f;
    public float stroke = 1.5f;

    public Sound burstSound = Sounds.laser;

    public ExplodeDrill(String name){
        super(name);

        hardnessDrillMultiplier = 0f;
        liquidBoostIntensity = 1f;
    }

    @Override
    public void load() {
        super.load();
        bottom = Core.atlas.find(name + "-bottom");
        plasmaRegions = new TextureRegion[plasmas];
        for(int i = 0; i < plasmaRegions.length; i++){
            plasmaRegions[i] = Core.atlas.find(name + "-plasma-" + i);
        }
    }

    @Override
    public void setStats(){
        super.setStats();
        stats.add(Stat.drillSpeed, 60f / drillTimeBurst * size * size, StatUnit.itemsSecond);
        stats.add(Stat.drillSpeed, digAmount/(reFindTimer/60), StatUnit.itemsSecond);
        if(coolant != null){
            stats.add(Stat.booster, EUStatValues.stringBoosters(drillTime, coolant.amount, coolantMultiplier, false, l -> l.coolant && consumesLiquid(l), "stat.extra-utilities-upSpeed"));
        }
        stats.add(Stat.range, Core.bundle.format("stat.extra-utilities-digRange", reRange - size/2));
    }

    @Override
    public void setBars(){
        super.setBars();
        addBar(ExtraUtilitiesMod.name("drillspeed"), (ExplodeDrillBuild e) ->
                new Bar(
                        () -> Core.bundle.format("bar." + ExtraUtilitiesMod.name("drillspeed"), Strings.fixed(e.lastDrillSpeedBurst * 60 * e.timeScale(), 2)),
                        () -> Pal.ammo, () -> e.warmupBurst
                ));
        addBar(ExtraUtilitiesMod.name("digspeed"), (ExplodeDrillBuild e) ->
                new Bar(
                        () -> Core.bundle.format("bar." + ExtraUtilitiesMod.name("digspeed"), Strings.fixed(digAmount/(reFindTimer/60) * e.timeScale() * e.efficiency, 2)),
                        () -> Pal.ammo, () -> e.efficiency
                ));
    }

    public float getDrillTimeBurst(Item item){
        return drillTimeBurst / drillMultipliers.get(item, 1f);
    }

    @Override
    public TextureRegion[] icons(){
        return new TextureRegion[]{bottom, region};
    }

    @Override
    public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list){
        Draw.rect(bottom, plan.drawx(), plan.drawy());
        Draw.rect(region, plan.drawx(), plan.drawy());
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        x *= tilesize;
        y *= tilesize;

        Drawf.dashSquare(Pal.accent, x, y, (reRange + 0.5f) * tilesize * 2);
    }

    public class ExplodeDrillBuild extends DrillBuild{
        public float smoothProgress = 0f;
        public float invertTime = 0f;

        public float progressBurst;
        public float warmupBurst;
        public float timeDrilledBurst;
        public float lastDrillSpeedBurst;

        public float reFindTime = 0;

        public Seq<Tile> tiles = new Seq<>();

        private boolean validOre(Tile tile, Item item){
            return (tile.solid() && tile.wallDrop() != null && tile.wallDrop() == item) || (tile.block() == Blocks.air && tile.drop() != null && tile.drop() == item);
        }

        public void findOre(Item item){
            tiles.clear();
            int tx = World.toTile(x), ty = World.toTile(y);
            for(int x = -reRange; x <= reRange; x++) {
                for (int y = -reRange; y <= reRange; y++) {
                    Tile t = world.tile(x + tx, y + ty);
                    if(t != null && validOre(t, item)) tiles.addUnique(t);
                }
            }
        }
        
        @Override
        public void updateTile() {
            if(dominantItem == null) return;

            if(invertTime > 0f) invertTime -= delta() / invertedTime;

            if(timer(timerDump, dumpTime)){
                for(int i = this.items.total(); i > 0; i--)
                    dump(items.has(dominantItem) ? dominantItem : null);
            }

            timeDrilled += warmup * delta();

            float delay = getDrillTime(dominantItem);

            if(items.total() < itemCapacity && dominantItems > 0 && efficiency > 0){
                float speed = Mathf.lerp(1f, liquidBoostIntensity, optionalEfficiency) * efficiency * EFF();

                lastDrillSpeed = (speed * dominantItems * warmup) / delay;
                warmup = Mathf.approachDelta(warmup, speed, warmupSpeed);
                progress += delta() * dominantItems * speed * warmup;

                if(Mathf.chanceDelta(updateEffectChance * warmup))
                    updateEffect.at(x + Mathf.range(size * 2f), y + Mathf.range(size * 2f));
            }else{
                lastDrillSpeed = 0f;
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
                return;
            }

            if(dominantItems > 0 && progress >= delay && items.total() < itemCapacity){
                offload(dominantItem);

                progress %= delay;

                if(wasVisible) drillEffect.at(x + Mathf.range(drillEffectRnd), y + Mathf.range(drillEffectRnd), dominantItem.color);
            }

            float drillTime = getDrillTimeBurst(dominantItem);

            smoothProgress = Mathf.lerpDelta(smoothProgress, progressBurst / (drillTime - 20f), 0.1f);

            if(items.total() <= itemCapacity - dominantItems && dominantItems > 0 && efficiency > 0){
                warmupBurst = Mathf.approachDelta(warmupBurst, progressBurst / drillTime, 0.01f);

                float speed = efficiency * EFF() * EFF();

                timeDrilledBurst += speedCurve.apply(progressBurst / drillTime) * speed;

                lastDrillSpeedBurst = 1f / drillTime * speed * dominantItems;
                progressBurst += delta() * speed;
            }else{
                warmupBurst = Mathf.approachDelta(warmupBurst, 0f, 0.01f);
                lastDrillSpeedBurst = 0f;
                return;
            }

            if(dominantItems > 0 && progressBurst >= drillTime && items.total() < itemCapacity){
                for(int i = 0; i < dominantItems; i++){
                    offload(dominantItem);
                }

                invertTime = 1f;
                progressBurst %= drillTime;

                if(wasVisible){
                    Effect.shake(shake, shake, this);
                    burstSound.at(x, y, 1, 0.3f);
                    //到时候在独立？
//                    for(int i = 0; i < 6; i++) {
//                        float rx = x + Mathf.range(size * size), ry = y + Mathf.range(size * size);
//                        EUFx.gone(dominantItem.color, 10, 5).at(rx, ry);
//                        Fx.chainLightning.at(rx, ry, 0, glowColor, this);
//                    }
                    EUFx.expDillEffect(size * 2, glowColor).at(this);
                }
            }

            if(reRange <= 0) return;
            if(this.items.total() < itemCapacity && (reFindTime += edelta()) > reFindTimer){
                findOre(dominantItem);
                if(tiles.size > 0){
                    int i = Mathf.random(tiles.size - 1);
                    Tile t = tiles.get(i);
                    EUFx.digTile(dominantItem.color).at(t);
                    Fx.chainLightning.at(t.worldx(), t.worldy(), 0, dominantItem.color, this);
                    InputHandler.createItemTransfer(dominantItem, 2, t.worldx(), t.worldy(), this, () -> {
                        for(int o = 0; o < digAmount; o++) offload(dominantItem);
                    });
                }
                reFindTime -= reFindTimer;
            }
        }

        @Override
        public void draw() {
            Color dc = dominantItem == null ? Color.white : dominantItem.color;
            Draw.rect(bottom, x, y);
            Draw.blend(Blending.additive);
            for(int i = 0; i < plasmaRegions.length; i++){
                float r = ((float)plasmaRegions[i].width * 1.2f * Draw.scl - 3f + Mathf.absin(Time.time, 2f + i * 1f, 5f - i * 0.5f));

                Draw.color(dc, glowColor, (float)i / plasmaRegions.length);
                Draw.alpha((0.3f + Mathf.absin(Time.time, 2f + i * 2f, 0.3f + i * 0.05f)) * warmup);
                Draw.rect(plasmaRegions[i], x, y, r, r, totalProgress()/2 * (12 + i * 6f));
            }
            Draw.color();
            Draw.blend();
            Draw.rect(region, x, y);
            if(drawRim){
                Draw.color(glowColor);
                Draw.alpha(warmup * 0.6f * (1f - 0.2f + Mathf.absin(Time.time, 4f, 0.2f)));
                Draw.blend(Blending.additive);
                Draw.rect(rimRegion, x, y);
                Draw.blend();
                Draw.color();
            }
            Draw.color(dc);
            Draw.rect(itemRegion, x, y);
            Draw.z(Layer.bullet-0.01f);
            Lines.stroke(stroke);
            DrawFunc.circlePercent(x, y, circleRange > 0 ? circleRange : size * size, warmup, 135f);
            Draw.color(glowColor);
            DrawFunc.circlePercent(x, y, circleRange > 0 ? circleRange : size * size, Math.min(warmupBurst, warmup), 135f);
            Draw.color();
        }

        @Override
        public void drawSelect() {
            super.drawSelect();
            if(reRange <= 0) return;
            Drawf.dashSquare(Pal.accent, x, y, (reRange + 0.5f) * tilesize * 2);
            Color dc = dominantItem == null ? Color.white : dominantItem.color;
            float sin = Mathf.absin(Time.time, 4, 1);
            for(int i = 0; i < tiles.size; i++){
                Tile t = tiles.get(i);
                Draw.color(dc);
                Draw.alpha(sin);
                Fill.square(t.worldx(), t.worldy(), tilesize/2f);
            }
        }

        public float EFF() {
            Liquid liquid = liquids.current();
            return (liquid.heatCapacity - Liquids.water.heatCapacity + 1) * coolantMultiplier;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.f(progressBurst);
            write.f(warmupBurst);
            write.f(reFindTime);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            if(revision >= 1){
                progressBurst = read.f();
                warmupBurst = read.f();
                reFindTime = read.f();
            }
        }
    }
}
