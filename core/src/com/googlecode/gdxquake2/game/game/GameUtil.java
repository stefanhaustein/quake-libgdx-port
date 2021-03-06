/*
 * Copyright (C) 1997-2001 Id Software, Inc.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.
 * 
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 *  
 */
/* Modifications
   Copyright 2003-2004 Bytonic Software
   Copyright 2010 Google Inc.
*/
package com.googlecode.gdxquake2.game.game;

import com.googlecode.gdxquake2.game.client.ClientMonsterMethods;
import com.googlecode.gdxquake2.game.common.CM;
import com.googlecode.gdxquake2.game.common.Com;
import com.googlecode.gdxquake2.game.common.Constants;
import com.googlecode.gdxquake2.game.common.Globals;
import com.googlecode.gdxquake2.game.game.adapters.EntityThinkAdapter;
import com.googlecode.gdxquake2.game.game.adapters.EntityUseAdapter;
import com.googlecode.gdxquake2.game.server.ServerGame;
import com.googlecode.gdxquake2.game.server.ServerInit;
import com.googlecode.gdxquake2.game.server.World;
import com.googlecode.gdxquake2.game.util.Lib;
import com.googlecode.gdxquake2.game.util.Math3D;


public class GameUtil {

    public static void checkClassname(Entity ent) {

        if (ent.classname == null) {
            Com.Printf("edict with classname = null: " + ent.index);
        }
    }

    /** 
     * Use the targets.
     * 
     * The global "activator" should be set to the entity that initiated the
     * firing.
     * 
     * If self.delay is set, a DelayedUse entity will be created that will
     * actually do the SUB_UseTargets after that many seconds have passed.
     * 
     * Centerprints any self.message to the activator.
     * 
     * Search for (string)targetname in all entities that match
     * (string)self.target and call their .use function
     */

    public static void G_UseTargets(Entity ent, Entity activator) {
        Entity t;

        checkClassname(ent);

        // check for a delay
        if (ent.delay != 0) {
            // create a temp object to fire at a later time
            t = G_Spawn();
            t.classname = "DelayedUse";
            t.nextthink = GameBase.level.time + ent.delay;
            t.think = Think_Delay;
            t.activator = activator;
            if (activator == null)
              ServerGame.PF_dprintf("Think_Delay with no activator\n");
            t.message = ent.message;
            t.target = ent.target;
            t.killtarget = ent.killtarget;
            return;
        }


        // print the message
        if ((ent.message != null)
                && (activator.svflags & Constants.SVF_MONSTER) == 0) {
            ServerGame.PF_centerprintf(activator, "" + ent.message);
            if (ent.noise_index != 0)
              ServerGame.PF_StartSound(activator, Constants.CHAN_AUTO, ent.noise_index, (float) 1, (float) Constants.ATTN_NORM,
              (float) 0);
            else
              ServerGame.PF_StartSound(activator, Constants.CHAN_AUTO, ServerInit.SV_SoundIndex("misc/talk1.wav"), (float) 1, (float) Constants.ATTN_NORM,
              (float) 0);
        }

        // kill killtargets
        EntityIterator edit = null;

        if (ent.killtarget != null) {
            while ((edit = GameBase.G_Find(edit, GameBase.findByTarget,
                    ent.killtarget)) != null) {
                t = edit.o;
                G_FreeEdict(t);
                if (!ent.inuse) {
                    ServerGame.PF_dprintf("entity was removed while using killtargets\n");
                    return;
                }
            }
        }

        // fire targets
        if (ent.target != null) {
            edit = null;
            while ((edit = GameBase.G_Find(edit, GameBase.findByTarget,
                    ent.target)) != null) {
                t = edit.o;
                // doors fire area portals in a specific way
                if (Lib.Q_stricmp("func_areaportal", t.classname) == 0
                        && (Lib.Q_stricmp("func_door", ent.classname) == 0 || Lib
                                .Q_stricmp("func_door_rotating", ent.classname) == 0))
                    continue;

                if (t == ent) {
                    ServerGame.PF_dprintf("WARNING: Entity used itself.\n");
                } else {
                    if (t.use != null)
                        t.use.use(t, ent, activator);
                }
                if (!ent.inuse) {
                    ServerGame.PF_dprintf("entity was removed while using targets\n");
                    return;
                }
            }
        }
    }

    public static void G_InitEdict(Entity e, int i) {
        e.inuse = true;
        e.classname = "noclass";
        e.gravity = 1.0f;
        //e.s.number= e - g_edicts;
        e.s = new EntityState(e);
        e.s.number = i;
        e.index = i;
    }

    /**
     * Either finds a free edict, or allocates a new one. Try to avoid reusing
     * an entity that was recently freed, because it can cause the client to
     * think the entity morphed into something else instead of being removed and
     * recreated, which can cause interpolated angles and bad trails.
     */
    public static Entity G_Spawn() {
        int i;
        Entity e = null;

        for (i = (int) GameBase.maxclients.value + 1; i < GameBase.num_edicts; i++) {
            e = GameBase.g_edicts[i];
            // the first couple seconds of server time can involve a lot of
            // freeing and allocating, so relax the replacement policy
            if (!e.inuse
                    && (e.freetime < 2 || GameBase.level.time - e.freetime > 0.5)) {
                e = GameBase.g_edicts[i] = new Entity(i);
                G_InitEdict(e, i);
                return e;
            }
        }

        if (i == GameBase.game.maxentities)
          Com.Error(Constants.ERR_FATAL, "ED_Alloc: no free edicts");

        e = GameBase.g_edicts[i] = new Entity(i);
        GameBase.num_edicts++;
        G_InitEdict(e, i);
        return e;
    }

    /**
     * Marks the edict as free
     */
    public static void G_FreeEdict(Entity ed) {
        World.SV_UnlinkEdict(ed); // unlink from world

        //if ((ed - g_edicts) <= (maxclients.value + BODY_QUEUE_SIZE))
        if (ed.index <= (GameBase.maxclients.value + Constants.BODY_QUEUE_SIZE)) {
            // gi.dprintf("tried to free special edict\n");
            return;
        }

        GameBase.g_edicts[ed.index] = new Entity(ed.index);
        ed.classname = "freed";
        ed.freetime = GameBase.level.time;
        ed.inuse = false;
    }

    /**
     * Call after linking a new trigger in during gameplay to force all entities
     * it covers to immediately touch it.
     */

    public static void G_ClearEdict(Entity ent) {
        int i = ent.index;
        GameBase.g_edicts[i] = new Entity(i);
    }


    /**
     * Kills all entities that would touch the proposed new positioning of ent.
     * Ent should be unlinked before calling this!
     */

    public static boolean KillBox(Entity ent) {
        Trace tr;

        while (true) {
            tr = World.SV_Trace(ent.s.origin, ent.mins, ent.maxs, ent.s.origin, null, Constants.MASK_PLAYERSOLID);
            if (tr.ent == null || tr.ent == GameBase.g_edicts[0])
                break;

            // nail it
            GameCombat.T_Damage(tr.ent, ent, ent, Globals.vec3_origin, ent.s.origin,
                    Globals.vec3_origin, 100000, 0,
                    Constants.DAMAGE_NO_PROTECTION, Constants.MOD_TELEFRAG);

            // if we didn't kill it, fail
            if (tr.ent.solid != 0)
                return false;
        }

        return true; // all clear
    }

    /** 
     * Returns true, if two edicts are on the same team. 
     */
    public static boolean OnSameTeam(Entity ent1, Entity ent2) {
        if (0 == ((int) (GameBase.dmflags.value) & (Constants.DF_MODELTEAMS | Constants.DF_SKINTEAMS)))
            return false;

        if (ClientTeam(ent1).equals(ClientTeam(ent2)))
            return true;
        return false;
    }

    /** 
     * Returns the team string of an entity 
     * with respect to rteam_by_model and team_by_skin. 
     */
    static String ClientTeam(Entity ent) {
        String value;

        if (ent.client == null)
            return "";

        value = Info.Info_ValueForKey(ent.client.pers.userinfo, "skin");

        int p = value.indexOf("/");

        if (p == -1)
            return value;

        if (((int) (GameBase.dmflags.value) & Constants.DF_MODELTEAMS) != 0) {
            return value.substring(0, p);
        }

        return value.substring(p + 1, value.length());
    }

    static void ValidateSelectedItem(Entity ent) {
        GameClient cl;

        cl = ent.client;

        if (cl.pers.inventory[cl.pers.selected_item] != 0)
            return; // valid

        GameItems.SelectNextItem(ent, -1);
    }

    /**
     * Returns the range catagorization of an entity reletive to self 0 melee
     * range, will become hostile even if back is turned 1 visibility and
     * infront, or visibility and show hostile 2 infront and show hostile 3 only
     * triggered by damage.
     */
    public static int range(Entity self, Entity other) {
        float[] v = { 0, 0, 0 };
        float len;

        Math3D.VectorSubtract(self.s.origin, other.s.origin, v);
        len = Math3D.VectorLength(v);
        if (len < Constants.MELEE_DISTANCE)
            return Constants.RANGE_MELEE;
        if (len < 500)
            return Constants.RANGE_NEAR;
        if (len < 1000)
            return Constants.RANGE_MID;
        return Constants.RANGE_FAR;
    }

    static void AttackFinished(Entity self, float time) {
        self.monsterinfo.attack_finished = GameBase.level.time + time;
    }

    /**
     * Returns true if the entity is in front (in sight) of self
     */
    public static boolean infront(Entity self, Entity other) {
        float[] vec = { 0, 0, 0 };
        float dot;
        float[] forward = { 0, 0, 0 };

        Math3D.AngleVectors(self.s.angles, forward, null, null);
        Math3D.VectorSubtract(other.s.origin, self.s.origin, vec);
        Math3D.VectorNormalize(vec);
        dot = Math3D.DotProduct(vec, forward);

        if (dot > 0.3)
            return true;
        return false;
    }

    /**
     * Returns 1 if the entity is visible to self, even if not infront().
     */
    public static boolean visible(Entity self, Entity other) {
        float[] spot1 = { 0, 0, 0 };
        float[] spot2 = { 0, 0, 0 };
        Trace trace;

        Math3D.VectorCopy(self.s.origin, spot1);
        spot1[2] += self.viewheight;
        Math3D.VectorCopy(other.s.origin, spot2);
        spot2[2] += other.viewheight;
        trace = World.SV_Trace(spot1, Globals.vec3_origin, Globals.vec3_origin, spot2, self, Constants.MASK_OPAQUE);

        if (trace.fraction == 1.0)
            return true;
        return false;
    }

    /**
     * Finds a target.
     * 
     * Self is currently not attacking anything, so try to find a target
     * 
     * Returns TRUE if an enemy was sighted
     * 
     * When a player fires a missile, the point of impact becomes a fakeplayer
     * so that monsters that see the impact will respond as if they had seen the
     * player.
     * 
     * To avoid spending too much time, only a single client (or fakeclient) is
     * checked each frame. This means multi player games will have slightly
     * slower noticing monsters.
     */
    static boolean FindTarget(Entity self) {
        Entity client;
        boolean heardit;
        int r;

        if ((self.monsterinfo.aiflags & Constants.AI_GOOD_GUY) != 0) {
            if (self.goalentity != null && self.goalentity.inuse
                    && self.goalentity.classname != null) {
                if (self.goalentity.classname.equals("target_actor"))
                    return false;
            }
            
            //FIXME look for monsters?
            return false;
        }

        // if we're going to a combat point, just proceed
        if ((self.monsterinfo.aiflags & Constants.AI_COMBAT_POINT) != 0)
            return false;

        // if the first spawnflag bit is set, the monster will only wake up on
        // really seeing the player, not another monster getting angry or
        // hearing something
        // revised behavior so they will wake up if they "see" a player make a
        // noise but not weapon impact/explosion noises

        heardit = false;
        if ((GameBase.level.sight_entity_framenum >= (GameBase.level.framenum - 1))
                && 0 == (self.spawnflags & 1)) {
            client = GameBase.level.sight_entity;           
            if (client.enemy == self.enemy)             
                return false;            
        } else if (GameBase.level.sound_entity_framenum >= (GameBase.level.framenum - 1)) {
            client = GameBase.level.sound_entity;
            heardit = true;
        } else if (null != (self.enemy)
                && (GameBase.level.sound2_entity_framenum >= (GameBase.level.framenum - 1))
                && 0 != (self.spawnflags & 1)) {
            client = GameBase.level.sound2_entity;
            heardit = true;
        } else {
            client = GameBase.level.sight_client;
            if (client == null)
                return false; // no clients to get mad at
        }

        // if the entity went away, forget it
        if (!client.inuse)
            return false;

        if (client.client != null) {
            if ((client.flags & Constants.FL_NOTARGET) != 0)
                return false;
        } else if ((client.svflags & Constants.SVF_MONSTER) != 0) {
            if (client.enemy == null)
                return false;
            if ((client.enemy.flags & Constants.FL_NOTARGET) != 0)
                return false;
        } else if (heardit) {
            if ((client.owner.flags & Constants.FL_NOTARGET) != 0)
                return false;
        } else
            return false;

        if (!heardit) {
            r = range(self, client);

            if (r == Constants.RANGE_FAR)
                return false;

            // this is where we would check invisibility
            // is client in an spot too dark to be seen?
            
            if (client.light_level <= 5)
                return false;

            if (!visible(self, client)) 
                return false;
           

            if (r == Constants.RANGE_NEAR) {
                if (client.show_hostile < GameBase.level.time
                        && !infront(self, client))               
                    return false;                
            } else if (r == Constants.RANGE_MID) {
                if (!infront(self, client)) 
                    return false;               
            }

            if (client == self.enemy)
                return true; // JDC false;
            
            self.enemy = client;

            if (!self.enemy.classname.equals("player_noise")) {
                self.monsterinfo.aiflags &= ~Constants.AI_SOUND_TARGET;

                if (self.enemy.client == null) {
                    self.enemy = self.enemy.enemy;
                    if (self.enemy.client == null) {
                        self.enemy = null;
                        return false;
                    }
                }
            }
        } else {
            // heard it
            float[] temp = { 0, 0, 0 };

            if ((self.spawnflags & 1) != 0) {
                if (!visible(self, client))
                    return false;
            } else {
                if (!ServerGame.PF_inPHS(self.s.origin, client.s.origin))
                    return false;
            }

            Math3D.VectorSubtract(client.s.origin, self.s.origin, temp);

            if (Math3D.VectorLength(temp) > 1000) // too far to hear
                return false;


            // check area portals - if they are different and not connected then
            // we can't hear it
            if (client.areanum != self.areanum)
                if (!CM.CM_AreasConnected(self.areanum, client.areanum))
                    return false;

            self.ideal_yaw = Math3D.vectoyaw(temp);
            ClientMonsterMethods.M_ChangeYaw(self);

            // hunt the sound for a bit; hopefully find the real player
            self.monsterinfo.aiflags |= Constants.AI_SOUND_TARGET;
            
            if (client == self.enemy)
                return true; // JDC false;
             
            self.enemy = client;             
        }
        
        // got one
        FoundTarget(self);

        if (0 == (self.monsterinfo.aiflags & Constants.AI_SOUND_TARGET)
                && (self.monsterinfo.sight != null))
            self.monsterinfo.sight.interact(self, self.enemy);

        return true;
    }

    public static void FoundTarget(Entity self) {
        // let other monsters see this monster for a while
        if (self.enemy.client != null) {
            GameBase.level.sight_entity = self;
            GameBase.level.sight_entity_framenum = GameBase.level.framenum;
            GameBase.level.sight_entity.light_level = 128;
        }

        self.show_hostile = (int) GameBase.level.time + 1; // wake up other
                                                           // monsters

        Math3D.VectorCopy(self.enemy.s.origin, self.monsterinfo.last_sighting);
        self.monsterinfo.trail_time = GameBase.level.time;

        if (self.combattarget == null) {
            GameAI.HuntTarget(self);
            return;
        }

        self.goalentity = self.movetarget = GameBase
                .G_PickTarget(self.combattarget);
        if (self.movetarget == null) {
            self.goalentity = self.movetarget = self.enemy;
            GameAI.HuntTarget(self);
            ServerGame.PF_dprintf("" + self.classname + "at "
            + Lib.vtos(self.s.origin) + ", combattarget "
            + self.combattarget + " not found\n");
            return;
        }

        // clear out our combattarget, these are a one shot deal
        self.combattarget = null;
        self.monsterinfo.aiflags |= Constants.AI_COMBAT_POINT;

        // clear the targetname, that point is ours!
        self.movetarget.targetname = null;
        self.monsterinfo.pausetime = 0;

        // run for it
        self.monsterinfo.run.think(self);
    }

    public static EntityThinkAdapter Think_Delay = new EntityThinkAdapter() {
    	public String getID() { return "Think_Delay"; }
        public boolean think(Entity ent) {
            G_UseTargets(ent, ent.activator);
            G_FreeEdict(ent);
            return true;
        }
    };

    public static EntityThinkAdapter G_FreeEdictA = new EntityThinkAdapter() {
    	public String getID() { return "G_FreeEdictA"; }
        public boolean think(Entity ent) {
            G_FreeEdict(ent);
            return false;
        }
    };

    static EntityThinkAdapter MegaHealth_think = new EntityThinkAdapter() {
    	public String getID() { return "MegaHealth_think"; }
        public boolean think(Entity self) {
            if (self.owner.health > self.owner.max_health) {
                self.nextthink = GameBase.level.time + 1;
                self.owner.health -= 1;
                return false;
            }

            if (!((self.spawnflags & Constants.DROPPED_ITEM) != 0)
                    && (GameBase.deathmatch.value != 0))
                GameItems.SetRespawn(self, 20);
            else
                G_FreeEdict(self);

            return false;
        }
    };


    public static EntityThinkAdapter M_CheckAttack = new EntityThinkAdapter() {
    	public String getID() { return "M_CheckAttack"; }

        public boolean think(Entity self) {
            float[] spot1 = { 0, 0, 0 };

            float[] spot2 = { 0, 0, 0 };
            float chance;
            Trace tr;

            if (self.enemy.health > 0) {
                // see if any entities are in the way of the shot
                Math3D.VectorCopy(self.s.origin, spot1);
                spot1[2] += self.viewheight;
                Math3D.VectorCopy(self.enemy.s.origin, spot2);
                spot2[2] += self.enemy.viewheight;

                tr = World.SV_Trace(spot1, null, null, spot2, self, Constants.CONTENTS_SOLID | Constants.CONTENTS_MONSTER
                | Constants.CONTENTS_SLIME
                | Constants.CONTENTS_LAVA
                | Constants.CONTENTS_WINDOW);

                // do we have a clear shot?
                if (tr.ent != self.enemy)
                    return false;
            }

            // melee attack
            if (GameAI.enemy_range == Constants.RANGE_MELEE) {
                // don't always melee in easy mode
                if (GameBase.skill.value == 0 && (Lib.rand() & 3) != 0)
                    return false;
                if (self.monsterinfo.melee != null)
                    self.monsterinfo.attack_state = Constants.AS_MELEE;
                else
                    self.monsterinfo.attack_state = Constants.AS_MISSILE;
                return true;
            }

            // missile attack
            if (self.monsterinfo.attack == null)
                return false;

            if (GameBase.level.time < self.monsterinfo.attack_finished)
                return false;

            if (GameAI.enemy_range == Constants.RANGE_FAR)
                return false;

            if ((self.monsterinfo.aiflags & Constants.AI_STAND_GROUND) != 0) {
                chance = 0.4f;
            } else if (GameAI.enemy_range == Constants.RANGE_MELEE) {
                chance = 0.2f;
            } else if (GameAI.enemy_range == Constants.RANGE_NEAR) {
                chance = 0.1f;
            } else if (GameAI.enemy_range == Constants.RANGE_MID) {
                chance = 0.02f;
            } else {
                return false;
            }

            if (GameBase.skill.value == 0)
                chance *= 0.5;
            else if (GameBase.skill.value >= 2)
                chance *= 2;

            if (Lib.random() < chance) {
                self.monsterinfo.attack_state = Constants.AS_MISSILE;
                self.monsterinfo.attack_finished = GameBase.level.time + 2
                        * Lib.random();
                return true;
            }

            if ((self.flags & Constants.FL_FLY) != 0) {
                if (Lib.random() < 0.3f)
                    self.monsterinfo.attack_state = Constants.AS_SLIDING;
                else
                    self.monsterinfo.attack_state = Constants.AS_STRAIGHT;
            }

            return false;

        }
    };

    static EntityUseAdapter monster_use = new EntityUseAdapter() {
    	public String getID() { return "monster_use"; }
        public void use(Entity self, Entity other, Entity activator) {
            if (self.enemy != null)
                return;
            if (self.health <= 0)
                return;
            if ((activator.flags & Constants.FL_NOTARGET) != 0)
                return;
            if ((null == activator.client)
                    && 0 == (activator.monsterinfo.aiflags & Constants.AI_GOOD_GUY))
                return;

            // delay reaction so if the monster is teleported, its sound is
            // still heard
            self.enemy = activator;
            FoundTarget(self);
        }
    };
}
