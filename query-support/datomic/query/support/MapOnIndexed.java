// Copyright (c) Cognitect, Inc.
// All rights reserved.

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS-IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package datomic.query.support;

import clojure.lang.*;

import java.util.Iterator;

public class MapOnIndexed extends clojure.lang.APersistentMap implements clojure.lang.IObj, Indexed{
final public IPersistentVector keys;
final public Indexed vals;
final public IPersistentMap meta;

public MapOnIndexed(IPersistentVector keys, Indexed vals){
    this(null,keys,vals);
}

public MapOnIndexed(IPersistentMap meta, IPersistentVector keys, Indexed vals){
    this.meta = meta;
    this.keys = keys;
    this.vals = vals;
}

private int indexOfObject(Object k){
    Util.EquivPred ep = Util.equivPred(k);
    for(int i = 0; i < keys.count(); i++)
        {
        if(ep.equiv(k, keys.nth(i)))
            return i;
        }
	return -1;
}

private int indexOf(Object k){
    if(k instanceof Keyword)
        {
        for(int i = 0; i < keys.count(); i++)
            {
            if(k == keys.nth(i))
                return i;
            }
    	return -1;
        }
    else
        return indexOfObject(k);
}

@Override
public boolean containsKey(Object k){
    return indexOf(k) >= 0;
}

@Override
public IMapEntry entryAt(Object key){
    int i = indexOf(key);
   	if(i >= 0)
   		return (IMapEntry) MapEntry.create(keys.nth(i), vals.nth(i));
   	return null;
}

private IPersistentMap dupe(){
    return PersistentArrayMap.create(this);
}

@Override
public IPersistentMap assoc(Object k, Object v){
    return dupe().assoc(k,v);
}

@Override
public IPersistentMap assocEx(Object k, Object v){
    return dupe().assocEx(k,v);
}

@Override
public IPersistentMap without(Object k){
    return dupe().without(k);
}

@Override
public Object valAt(Object k){
    return valAt(k, null);
}

@Override
public Object valAt(Object k, Object notFound){
    int i = indexOf(k);
   	if(i >= 0)
   		return vals.nth(i);
   	return notFound;
}

@Override
public IObj withMeta(IPersistentMap meta){
    if(meta() == meta)
   		return this;
   	return new datomic.query.support.MapOnIndexed(meta, keys, vals);
}

@Override
public IPersistentMap meta(){
    return meta;
}

@Override
public int count(){
    return keys.count();
}

@Override
public IPersistentCollection empty(){
    return PersistentArrayMap.EMPTY;
}

@Override
public Object nth(int i){
    return vals.nth(i);
}

@Override
public Object nth(int i, Object o){
    return vals.nth(i,o);
}

static class Seq extends ASeq implements Counted{
	final MapOnIndexed vmap;
	final int i;

	Seq(MapOnIndexed vmap, int i){
		this.vmap = vmap;
		this.i = i;
	}

	public Seq(IPersistentMap meta, MapOnIndexed vmap, int i){
		super(meta);
		this.vmap = vmap;
		this.i = i;
	}

	public Object first(){
		return MapEntry.create(vmap.keys.nth(i),vmap.vals.nth(i));
	}

	public ISeq next(){
		if(i+1 < vmap.keys.count())
			return new Seq(vmap, i+1);
		return null;
	}

	public int count(){
		return vmap.keys.count();
	}

	public Obj withMeta(IPersistentMap meta){
		if(meta() == meta)
			return this;
		return new Seq(meta, vmap, i);
	}
}
@Override
public ISeq seq(){
    if(keys.count() > 0)
        return new Seq(this,0);
    return null;
}

@Override
public Iterator iterator(){
    return RT.iter(seq());
}

}
