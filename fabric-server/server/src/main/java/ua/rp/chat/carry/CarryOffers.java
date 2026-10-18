package ua.rp.chat.carry;
import java.util.*;
/** Short-lived consent tokens tied to the giver's exact current held identity. */
public final class CarryOffers {
 public record Offer(UUID id,UUID from,UUID to,String identity,long expires){}
 private final Map<UUID,Offer> offers=new LinkedHashMap<>();
 public UUID offer(UUID from,UUID to,String identity,long now){if(from.equals(to))throw new IllegalArgumentException("Self offer");cancel(from);UUID id=UUID.randomUUID();offers.put(id,new Offer(id,from,to,identity,now+30_000));return id;}
 public Offer get(UUID id){return offers.get(id);}
 public List<Offer> entries(){return List.copyOf(offers.values());}
 public Offer accept(UUID id,UUID to,String currentIdentity,long now){Offer offer=offers.get(id);if(offer==null||!offer.to().equals(to)||offer.expires()<=now||!offer.identity().equals(currentIdentity))throw new IllegalStateException("Offer expired or changed");offers.remove(id);return offer;}
 public void decline(UUID id,UUID to){Offer o=offers.get(id);if(o!=null&&o.to().equals(to))offers.remove(id);}
 public void cancel(UUID player){offers.values().removeIf(o->o.from().equals(player)||o.to().equals(player));}
 public void expire(long now){offers.values().removeIf(o->o.expires()<=now);}
}
