using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading.Tasks;
using Api.Models;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Documents;
using Microsoft.Azure.Documents.Client;
using Microsoft.Azure.Documents.Linq;
using Microsoft.Azure.Documents.Spatial;
using Microsoft.Azure.WebJobs;
using Microsoft.Azure.WebJobs.Host;
using Microsoft.Extensions.Logging;
using Newtonsoft.Json;

namespace Api.Functions
{
    public static class SitesStateImport
    {
        static HttpClient httpClient = new HttpClient();
        static string parkingApiUrl = "https://data.melbourne.vic.gov.au/resource/vh2v-4nfs.json?%24limit=10";
        
        [FunctionName("SitesStateImport")]
        public static async Task<IActionResult> RunAsync(
            //[TimerTrigger("0 */2 * * * *")]TimerInfo myTimer,
            [HttpTrigger(Microsoft.Azure.WebJobs.Extensions.Http.AuthorizationLevel.Anonymous, "post", Route = "sites/state/import")] Microsoft.AspNetCore.Http.HttpRequest req,
            [CosmosDB(ConnectionStringSetting = "CosmosDBConnectionString")] DocumentClient documentClient,
            ILogger log)
        {
            log.LogInformation($"Sites State import starting at: {DateTime.Now}");

            Uri collectionUri = UriFactory.CreateDocumentCollectionUri("parkingdb", "sitesstate");
            IDocumentQuery<SiteState> query = documentClient.CreateDocumentQuery<SiteState>(collectionUri,
               new FeedOptions() { PartitionKey = new PartitionKey(null) })
               .AsDocumentQuery();
            var oldSites = await query.ToDictionary(x => x.Id);
            // return new ObjectResult(oldSites);

            log.LogInformation($"Sites State import old records imported at: {DateTime.Now}");

            var newSites = new List<SiteState>();

            using (HttpResponseMessage res = await httpClient.GetAsync(parkingApiUrl))
            {
                using (HttpContent content = res.Content)
                {
                    var lastUpdate = DateTime.UtcNow;
                    dynamic result = JsonConvert.DeserializeObject(await content.ReadAsStringAsync());

                    log.LogInformation($"Sites State import origin downloaded at: {DateTime.Now}. Total sites {result.Count}");

                    foreach (var item in result) {
                        var newSite = new SiteState()
                        {
                            Id = item.bay_id.ToString(),
                            Status = item.status,
                            Location = new Point(
                                (double)item.location.longitude,
                                (double)item.location.latitude
                            ),
                            // LastUpdate = lastUpdate
                        };
                        
                        SiteState oldSite;
                        
                        if (!oldSites.TryGetValue(newSite.Id, out oldSite))
                        {
                            newSites.Add(newSite);
                        }
                        else
                        {
                            oldSites.Remove(newSite.Id);
                            if (oldSite.Status != newSite.Status) //|| !oldSite.Location.Equals(newSite.Location))
                            {
                                newSites.Add(newSite);
                            }
                        }
                    }
                }
            }

            log.LogInformation($"Sites State import processed at: {DateTime.Now}. Sites to upsert {newSites.Count}. Sites to delete {oldSites.Count}");

            foreach (var item in newSites)
            {
                await documentClient.UpsertDocumentAsync(collectionUri, item);
            }

            foreach (var keyPair in oldSites)
            {
                await documentClient.DeleteDocumentAsync(
                    UriFactory.CreateDocumentUri("parkingdb", "sitesstate", keyPair.Key),
                    new RequestOptions() { PartitionKey = new PartitionKey(null) });
            }

            return new NoContentResult();
        }
    }
}